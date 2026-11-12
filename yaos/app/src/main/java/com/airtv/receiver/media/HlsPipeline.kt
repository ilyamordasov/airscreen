package com.airtv.receiver.media

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Plays the native AirPlay HLS proxy URL on the TV, independent of screen mirroring. */
class HlsPipeline {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var generation = 0

    var onVideoSize: ((Int, Int) -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun setSurface(value: Surface?) {
        main.post {
            surface = value
            player?.setSurface(value)
        }
    }

    fun play(localUrl: String, startPositionSeconds: Float) {
        main.post {
            // The proxy is owned by this app. Never pass an arbitrary network URL to it.
            if (!localUrl.startsWith("http://localhost:") || !localUrl.endsWith(".m3u8")) {
                onError?.invoke("Некорректный адрес видеопотока AirPlay.")
                return@post
            }
            stopOnMain()
            val token = generation
            try {
                val next = MediaPlayer()
                player = next
                next.setSurface(surface)
                next.setOnVideoSizeChangedListener { _, width, height ->
                    if (token == generation && width > 0 && height > 0) onVideoSize?.invoke(width, height)
                }
                next.setOnInfoListener { _, what, _ ->
                    if (token == generation && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame?.invoke()
                    false
                }
                next.setOnPreparedListener {
                    if (token != generation) return@setOnPreparedListener
                    if (startPositionSeconds.isFinite() && startPositionSeconds > 0f) {
                        next.seekTo((startPositionSeconds * 1000).toInt().coerceAtLeast(0))
                    }
                    next.start()
                }
                next.setOnErrorListener { _, what, extra ->
                    if (token == generation) onError?.invoke("Не удалось воспроизвести AirPlay-видео ($what/$extra).")
                    true
                }
                next.setDataSource(localUrl)
                next.prepareAsync()
            } catch (error: Exception) {
                stopOnMain()
                onError?.invoke("Не удалось открыть AirPlay-видео: ${error.localizedMessage.orEmpty()}")
            }
        }
    }

    fun stop() { main.post { stopOnMain() } }

    private fun stopOnMain() {
        generation++
        player?.let { runCatching { it.reset(); it.release() } }
        player = null
    }
}
