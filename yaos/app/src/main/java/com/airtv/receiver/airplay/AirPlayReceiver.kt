/* Adapted for FreeCast TV, 2026; original AirTV authors credited in THIRD_PARTY_NOTICES.md. */
package com.airtv.receiver.airplay

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.media.AudioPipeline
import com.airtv.receiver.media.HlsPipeline
import com.airtv.receiver.media.VideoPipeline
import com.airtv.receiver.settings.ReceiverPreferences
import com.airtv.receiver.settings.ReceiverSettings
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

sealed class ReceiverState {
    object Stopped : ReceiverState()
    object Starting : ReceiverState()
    data class Advertising(val name: String, val port: Int, val address: String?) : ReceiverState()
    data class Connecting(val clientName: String) : ReceiverState()
    /** Published only after a decoder output buffer has been released to the live Surface. */
    data class Streaming(val clientName: String, val width: Int, val height: Int) : ReceiverState()
    data class AudioOnly(val clientName: String, val codec: String, val playable: Boolean) : ReceiverState()
    data class Failed(val reason: String) : ReceiverState()
}

/** Owns the native listener, real media pipelines and locally visible diagnostics. */
class AirPlayReceiver(private val context: Context) : AirPlayCallbacks {
    private val video = VideoPipeline()
    private val audio = AudioPipeline()
    private val hls = HlsPipeline(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ReceiverState) -> Unit>()
    private val receivedVideo = AtomicLong(0)

    @Volatile var state: ReceiverState = ReceiverState.Stopped
        private set
    @Volatile var settings: ReceiverSettings = ReceiverPreferences.load(context)
        private set
    @Volatile private var running = false
    @Volatile private var clientName = ""
    @Volatile private var sourceWidth = 0
    @Volatile private var sourceHeight = 0
    @Volatile private var listeningPort = 0
    @Volatile private var videoCodecName = "—"
    @Volatile private var audioCodecName = "—"
    @Volatile private var lastError = "—"
    @Volatile private var hlsPlaying = false
    @Volatile private var outputSurface: Surface? = null

    val receiverName: String get() = settings.name
    val advertisedDisplay: AdvertisedDisplay get() = settings.display()
    val isRunning: Boolean get() = running
    val videoFramesRendered: Long get() = video.framesRendered
    val videoFramesDropped: Long get() = video.framesDropped

    init {
        video.onDecodedSize = { width, height ->
            sourceWidth = width
            sourceHeight = height
            if (running && video.framesRendered > 0) publishStreaming()
        }
        video.onFirstFrame = { if (running) publishStreaming() }
        video.onError = { message ->
            lastError = message
            if (running) publish(ReceiverState.Failed(message))
        }
        audio.onError = { message ->
            lastError = message
            if (running && state is ReceiverState.AudioOnly) {
                publish(ReceiverState.AudioOnly(clientName, audioCodecName, false))
            }
        }
        hls.onVideoSize = { width, height ->
            sourceWidth = width
            sourceHeight = height
            if (running && hlsPlaying && state is ReceiverState.Streaming)
                publish(ReceiverState.Streaming(clientName.ifBlank { "iPhone" }, width, height))
        }
        hls.onFirstFrame = {
            if (running && hlsPlaying) publish(ReceiverState.Streaming(clientName.ifBlank { "iPhone" },
                sourceWidth.takeIf { it > 0 } ?: advertisedDisplay.width,
                sourceHeight.takeIf { it > 0 } ?: advertisedDisplay.height))
        }
        hls.onError = { message ->
            lastError = message
            if (running && hlsPlaying) publish(ReceiverState.Failed(message))
        }
    }

    fun addListener(listener: (ReceiverState) -> Unit) {
        listeners += listener
        mainHandler.post { if (listeners.contains(listener)) listener(state) }
    }
    fun removeListener(listener: (ReceiverState) -> Unit) { listeners -= listener }
    fun setSurface(surface: Surface?) {
        outputSurface = surface
        video.setSurface(if (hlsPlaying) null else surface)
        hls.setSurface(surface)
    }

    /** Called from the service's serial worker, never a native callback thread. */
    fun start(): Boolean {
        if (running) return true
        settings = ReceiverPreferences.load(context)
        lastError = "—"
        publish(ReceiverState.Starting)
        if (localIpAddresses().isEmpty()) {
            lastError = "Нет локальной сети. Подключите телевизор к Wi-Fi или Ethernet."
            publish(ReceiverState.Failed(lastError))
            return false
        }
        val display = advertisedDisplay
        val result = try {
            NativeAirPlay.nativeStart(
                name = receiverName,
                macAddress = DeviceIdentity.macAddressFrom(deviceSeed()),
                keyFilePath = File(context.filesDir, "airplay_pairing.key").absolutePath,
                width = display.width,
                height = display.height,
                fps = display.fps,
                logLevel = NativeAirPlay.LOG_INFO,
                callbacks = this,
            )
        } catch (error: LinkageError) {
            lastError = "Не удалось загрузить AirPlay для этого процессора: ${error.message}"
            -100
        } catch (error: Exception) {
            lastError = "Не удалось запустить AirPlay: ${error.message}"
            -101
        }
        if (result <= 0) {
            if (lastError == "—") lastError = "Не удалось открыть приём AirPlay (код $result)."
            running = false
            publish(ReceiverState.Failed(lastError))
            return false
        }
        listeningPort = result
        running = true
        publishAdvertising()
        return true
    }

    fun stop() {
        running = false
        runCatching { NativeAirPlay.nativeStop() }
            .onFailure { Log.w(TAG, "native stop failed", it) }
        video.stop()
        audio.stop()
        hls.stop()
        hlsPlaying = false
        receivedVideo.set(0)
        sourceWidth = 0
        sourceHeight = 0
        listeningPort = 0
        clientName = ""
        publish(ReceiverState.Stopped)
    }

    override fun onLog(level: Int, message: String) {
        if (level <= NativeAirPlay.LOG_ERROR) lastError = message.take(400)
    }

    override fun onClientConnect(name: String, model: String, deviceId: String): Boolean {
        if (!running) return false
        clientName = name.ifBlank { model.ifBlank { "iPhone" } }.take(80)
        if (state !is ReceiverState.Streaming) publish(ReceiverState.Connecting(clientName))
        return true
    }

    override fun onSessionStart() {
        if (running && video.framesRendered == 0L) publish(ReceiverState.Connecting(clientName))
    }

    override fun onSessionEnd() {
        hls.stop()
        hlsPlaying = false
        video.stop()
        audio.stop()
        receivedVideo.set(0)
        sourceWidth = 0
        sourceHeight = 0
        videoCodecName = "—"
        audioCodecName = "—"
        if (running) publishAdvertising()
    }

    override fun onVideoCodec(isH265: Boolean): Int {
        if (hlsPlaying) onHlsStop()
        videoCodecName = if (isH265) "H.265 / HEVC" else "H.264 / AVC"
        video.setCodec(isH265)
        return 0
    }

    override fun onVideoData(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (!running) return
        receivedVideo.incrementAndGet()
        video.submit(data, length, presentationTimeUs)
    }

    override fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return
        sourceWidth = width
        sourceHeight = height
        video.setSourceSize(width, height)
        if (running && video.framesRendered > 0) publishStreaming()
    }
    override fun onVideoFlush() { video.flush() }
    override fun onVideoPause() = Unit
    override fun onVideoResume() = Unit
    override fun onVideoStop() { onSessionEnd() }

    override fun onAudioFormat(contentType: Int, framesPerPacket: Int, usingScreen: Boolean, isMedia: Boolean) {
        audioCodecName = AudioPipeline.describeContentType(contentType)
        audio.setFormat(contentType, framesPerPacket)
        if (running && !usingScreen) {
            publish(ReceiverState.AudioOnly(clientName, audioCodecName, audio.isRunning))
        }
    }
    override fun onAudioData(data: ByteBuffer, length: Int, presentationTimeUs: Long) {
        if (running) audio.submit(data, length, presentationTimeUs)
    }
    override fun onAudioFlush() { audio.flush() }
    override fun onVolume(decibels: Float) { audio.setVolumeDecibels(decibels) }

    override fun onHlsPlay(localUrl: String, startPositionSeconds: Float) {
        if (!running) return
        hlsPlaying = true
        video.setSurface(null)
        video.stop()
        audio.stop()
        sourceWidth = 0
        sourceHeight = 0
        videoCodecName = "HLS"
        hls.play(localUrl, startPositionSeconds)
        publish(ReceiverState.Connecting(clientName.ifBlank { "iPhone" }))
    }

    override fun onHlsStop() {
        hls.stop()
        hlsPlaying = false
        video.setSurface(outputSurface)
        if (running) publishAdvertising()
    }

    /** Local text only: no upload, tracking service, registration or remote diagnostics. */
    fun diagnostics(): String {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .filter { info -> info.supportedTypes.any { it in setOf("video/avc", "video/hevc", "audio/mp4a-latm") } }
                .joinToString("\n") { info ->
                    "${info.name}: ${info.supportedTypes.filter { it.startsWith("video/") || it == "audio/mp4a-latm" }.joinToString()}"
                }
        }.getOrDefault("Не удалось получить список")
        val profile = advertisedDisplay
        return """
            FreeCast TV ${BuildConfig.VERSION_NAME}
            Устройство: ${Build.MANUFACTURER} ${Build.MODEL}
            Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}
            Процессор: ${Build.SUPPORTED_ABIS.joinToString()}
            Имя: $receiverName
            IPv4: ${localIpAddresses().joinToString().ifEmpty { "нет локального адреса" }}
            Приём AirPlay: ${if (running) "включён, порт $listeningPort" else "выключен"}
            Состояние: ${state.javaClass.simpleName}
            Профиль: ${profile.width} × ${profile.height}, ${profile.fps} fps
            Видео: $videoCodecName; $sourceWidth × $sourceHeight
            Пакеты видео: ${receivedVideo.get()}
            Кадры на Surface: ${video.framesRendered}; пропущено: ${video.framesDropped}
            Аудио: $audioCodecName; буферы: ${audio.framesPlayed}
            Последняя ошибка: $lastError

            Декодеры телевизора:
            $decoders

            AirPlay: UxPlay / AirTV, GPLv3.
            Кино с защитой DRM не поддерживается.
        """.trimIndent()
    }

    private fun publishStreaming() {
        if (!running || video.framesRendered <= 0L) return
        val width = sourceWidth.takeIf { it > 0 } ?: advertisedDisplay.width
        val height = sourceHeight.takeIf { it > 0 } ?: advertisedDisplay.height
        publish(ReceiverState.Streaming(clientName.ifBlank { "iPhone" }, width, height))
    }

    private fun publishAdvertising() {
        if (running) publish(ReceiverState.Advertising(receiverName, listeningPort, localIpAddresses().firstOrNull()))
    }

    private fun publish(next: ReceiverState) {
        state = next
        mainHandler.post {
            if (state === next) listeners.forEach { it(next) }
        }
    }

    private fun deviceSeed(): String = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER}-${Build.MODEL}"

    private fun localIpAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }.toList()
    }.getOrDefault(emptyList())

    private companion object { const val TAG = "FreeCastReceiver" }
}
