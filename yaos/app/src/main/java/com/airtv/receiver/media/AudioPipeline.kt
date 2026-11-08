package com.airtv.receiver.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * Decodes the AirPlay audio stream (AAC-ELD for mirroring, AAC-LC for some senders) and
 * plays it through an [AudioTrack].
 */
class AudioPipeline {

    /** AirPlay "ct" content types. */
    enum class Format(val contentType: Int, val displayName: String, val supported: Boolean) {
        /** Audio-only AirPlay; Android has no guaranteed ALAC decoder. */
        ALAC(2, "ALAC (Apple Lossless)", supported = false),
        AAC_LC(4, "AAC-LC", supported = true),

        /** What screen mirroring always uses. */
        AAC_ELD(8, "AAC-ELD", supported = true),
        ;

        companion object {
            fun of(contentType: Int): Format? = entries.firstOrNull { it.contentType == contentType }
        }
    }

    companion object {
        fun describeContentType(contentType: Int): String =
            Format.of(contentType)?.displayName ?: "unknown (ct=$contentType)"

        private const val TAG = "AudioPipeline"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val AAC_PROFILE_LC = 2
        private const val AAC_PROFILE_ELD = 39
        private const val MUTED_DB = -144f
        private const val MAX_PACKET_BYTES = 1024 * 1024
        private const val INPUT_TIMEOUT_US = 20_000L
        private const val OUTPUT_TIMEOUT_US = 20_000L
        private const val DRAIN_JOIN_TIMEOUT_MS = 500L
    }

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var format: Format? = null
    private var scratch = ByteArray(16 * 1024)
    private var drainThread: Thread? = null

    @Volatile
    private var draining = false
    @Volatile private var generation = 0L
    @Volatile var onError: ((String) -> Unit)? = null

    @Volatile
    private var gain = 1.0f

    @Volatile
    var framesPlayed = 0L
        private set

    val isRunning: Boolean get() = synchronized(lock) { codec != null }

    /** Called when the sender announces the audio format for a new stream. */
    fun setFormat(contentType: Int) {
        synchronized(lock) {
            val requested = Format.of(contentType)
            if (requested == null) {
                Log.w(TAG, "unknown audio content type $contentType, ignoring audio")
                stopLocked()
                return
            }
            if (requested == Format.ALAC) {
                // Only reached for audio-only AirPlay from senders that pick ALAC;
                // Android has no guaranteed ALAC decoder, so we stay silent instead of
                // feeding a decoder that will error out on every frame.
                Log.w(TAG, "ALAC audio is not supported on Android; audio disabled")
                stopLocked()
                format = requested
                return
            }
            if (format == requested && codec != null) return
            stopLocked()
            format = requested
            startLocked(requested)
        }
    }

    fun submit(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (length <= 0) return
        if (length > data.limit() || length > MAX_PACKET_BYTES) {
            onError?.invoke("Некорректный размер аудиопакета: $length байт.")
            return
        }
        synchronized(lock) {
            val activeCodec = codec ?: return
            if (scratch.size < length) scratch = ByteArray(length)
            data.position(0)
            data.get(scratch, 0, length)
            try {
                val index = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) return
                val input = activeCodec.getInputBuffer(index) ?: return
                input.clear()
                if (length > input.remaining()) {
                    activeCodec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
                    onError?.invoke("Аудиопакет превышает буфер декодера ТВ.")
                    return
                }
                input.put(scratch, 0, length)
                activeCodec.queueInputBuffer(index, 0, length, presentationTimeUs, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "audio decoder rejected input", e)
            }
        }
    }

    fun flush() {
        synchronized(lock) {
            // Retire the drain before resetting buffers: a concurrent codec.flush()
            // can invalidate an output index and leave a live codec with no drain.
            val target = format ?: return
            if (codec == null) return
            stopLocked()
            startLocked(target)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
            format = null
            framesPlayed = 0L
        }
    }

    /** AirPlay volume: 0 dB is loudest, -30 dB quietest, -144 dB muted. */
    fun setVolumeDecibels(decibels: Float) {
        gain = when {
            !decibels.isFinite() -> 0f
            decibels <= MUTED_DB -> 0f
            decibels >= 0f -> 1f
            else -> 10.0.pow(decibels / 20.0).toFloat().coerceIn(0f, 1f)
        }
        synchronized(lock) {
            try {
                track?.setVolume(gain)
            } catch (_: IllegalStateException) {
            }
        }
    }

    private fun startLocked(target: Format) {
        var candidateCodec: MediaCodec? = null
        var candidateTrack: AudioTrack? = null
        try {
            val csd = when (target) {
                Format.AAC_ELD -> AudioSpecificConfig.aacEld(SAMPLE_RATE, CHANNELS)
                Format.AAC_LC -> AudioSpecificConfig.aacLc(SAMPLE_RATE, CHANNELS)
                Format.ALAC -> return
            }
            val mediaFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS,
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    if (target == Format.AAC_ELD) AAC_PROFILE_ELD else AAC_PROFILE_LC,
                )
                setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }

            val newTrack = buildAudioTrack().also { candidateTrack = it }
            val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { candidateCodec = it }
            newCodec.configure(mediaFormat, null, null, 0)
            newCodec.start()
            newTrack.play()
            newTrack.setVolume(gain)

            codec = newCodec
            track = newTrack
            startDrainLocked(newCodec, newTrack)
            Log.i(TAG, "audio started: $target ${SAMPLE_RATE}Hz x$CHANNELS")
        } catch (e: Exception) {
            Log.e(TAG, "failed to start audio for $target", e)
            if (codec !== candidateCodec) runCatching { candidateCodec?.release() }
            if (track !== candidateTrack) runCatching { candidateTrack?.release() }
            stopLocked()
            onError?.invoke("Не удалось открыть аудиодекодер $target: ${e.message}")
        }
    }

    private fun buildAudioTrack(): AudioTrack {
        val channelMask =
            if (CHANNELS == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(8 * 1024)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    private fun startDrainLocked(activeCodec: MediaCodec, activeTrack: AudioTrack) {
        draining = true
        val myGeneration = ++generation
        drainThread = Thread({
            val info = MediaCodec.BufferInfo()
            var pcm = ByteArray(8 * 1024)
            while (draining && generation == myGeneration) {
                try {
                    val index = activeCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                    if (index < 0) continue
                    val output = activeCodec.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        output.position(info.offset)
                        output.get(pcm, 0, info.size)
                        activeTrack.write(pcm, 0, info.size)
                        if (generation == myGeneration) framesPlayed++
                    }
                    activeCodec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    break
                }
            }
        }, "airtv-audio-drain").apply { start() }
    }

    private fun stopLocked() {
        draining = false
        generation++
        drainThread?.let {
            if (it !== Thread.currentThread()) it.join(DRAIN_JOIN_TIMEOUT_MS)
        }
        drainThread = null
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

}
