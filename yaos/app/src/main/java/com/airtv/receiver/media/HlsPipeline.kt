package com.airtv.receiver.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer

/** Plays AirPlay video on the TV, independently of the screen mirror decoder. */
class HlsPipeline(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var generation = 0

    var onVideoSize: ((Int, Int) -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun setSurface(value: Surface?) {
        main.post {
            surface = value
            player?.setVideoSurface(value)
        }
    }

    fun play(localUrl: String, startPositionSeconds: Float) {
        main.post {
            val uri = Uri.parse(localUrl)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                onError?.invoke("Некорректный адрес видеопотока AirPlay.")
                return@post
            }
            stopOnMain()
            val token = generation
            try {
                val next = ExoPlayer.Builder(context).build()
                player = next
                next.setVideoSurface(surface)
                next.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (token == generation && videoSize.width > 0 && videoSize.height > 0) {
                            onVideoSize?.invoke(videoSize.width, videoSize.height)
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (token == generation) onFirstFrame?.invoke()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (token == generation) onError?.invoke(describeError(error))
                    }
                })
                val item = MediaItem.Builder().setUri(uri).apply {
                    if (uri.path?.endsWith(".m3u8", ignoreCase = true) == true) {
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    }
                }.build()
                next.setMediaItem(item)
                if (startPositionSeconds.isFinite() && startPositionSeconds > 0f) {
                    next.seekTo((startPositionSeconds * 1000).toLong().coerceAtLeast(0))
                }
                next.playWhenReady = true
                next.prepare()
            } catch (error: Exception) {
                stopOnMain()
                onError?.invoke("Не удалось открыть AirPlay-видео: ${error.localizedMessage.orEmpty()}")
            }
        }
    }

    fun stop() { main.post { stopOnMain() } }

    private fun stopOnMain() {
        generation++
        player?.release()
        player = null
    }

    private fun describeError(error: PlaybackException): String {
        var cause: Throwable? = error
        repeat(5) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val response = cause as HttpDataSource.InvalidResponseCodeException
                return "Не удалось загрузить ${resourceLabel(response.dataSpec.uri)}: HTTP ${response.responseCode}."
            }
            if (cause is HttpDataSource.HttpDataSourceException) {
                val failure = cause as HttpDataSource.HttpDataSourceException
                return "Не удалось загрузить ${resourceLabel(failure.dataSpec.uri)}: ${error.errorCodeName}."
            }
            cause = cause?.cause
        }
        return "Не удалось воспроизвести AirPlay-видео: ${error.errorCodeName}."
    }

    private fun resourceLabel(uri: Uri): String = when {
        uri.path?.endsWith(".m3u8", ignoreCase = true) == true -> "плейлист AirPlay"
        else -> "видеоданные AirPlay"
    }
}
