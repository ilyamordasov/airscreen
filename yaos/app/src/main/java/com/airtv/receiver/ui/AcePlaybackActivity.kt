package com.airtv.receiver.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Plays the local Ace Engine HLS proxy in FreeCast TV, without opening Ace Player. */
class AcePlaybackActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private var stopUrl: String? = null
    private var closed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = VideoView(this)
        status = TextView(this).apply {
            text = "Подключаемся к Ace Stream Engine…"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0x99000000.toInt())
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        setContentView(root)
        val contentId = intent.getStringExtra(EXTRA_CONTENT_ID)
        if (contentId == null || !contentId.matches(Regex("[a-fA-F0-9]{40}"))) {
            finish()
            return
        }
        video.setOnPreparedListener { player ->
            player.isLooping = false
            status.text = "Буферизация эфира…"
            video.start()
        }
        video.setOnInfoListener { _, what, _ ->
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status.visibility = View.GONE
            false
        }
        video.setOnErrorListener { _, what, extra ->
            status.visibility = TextView.VISIBLE
            status.text = "Эфир не воспроизводится ($what/$extra). Проверьте Ace Stream Engine и доступ к потоку."
            true
        }
        Thread {
            val result = runCatching {
                val endpoint = "http://127.0.0.1:6878/ace/manifest.m3u8?format=json&content_id=$contentId"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 15_000
                }
                try {
                    val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val engineError = response.opt("error")
                    if (engineError != null && engineError != JSONObject.NULL) throw IllegalStateException(engineError.toString())
                    val playback = response.getJSONObject("response")
                    val streamUrl = playback.getString("playback_url")
                    val commandUrl = playback.optString("command_url")
                    require(isLocalAceUrl(streamUrl)) { "Ace Engine вернул посторонний адрес видео" }
                    require(commandUrl.isEmpty() || isLocalAceUrl(commandUrl)) {
                        "Ace Engine вернул посторонний адрес управления"
                    }
                    streamUrl to commandUrl
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { (streamUrl, commandUrl) ->
                    stopUrl = commandUrl.takeIf { it.isNotEmpty() }
                    video.setVideoURI(Uri.parse(streamUrl))
                    video.requestFocus()
                }.onFailure {
                    status.text = "Не удалось открыть эфир в FreeCast TV. Запустите Ace Stream Engine: ${it.localizedMessage.orEmpty()}"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        closed = true
        video.stopPlayback()
        stopUrl?.let { url ->
            Thread {
                runCatching {
                    val connection = (URL("$url${if ('?' in url) '&' else '?'}method=stop")
                        .openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2_000
                        readTimeout = 2_000
                    }
                    try { connection.inputStream.close() } finally { connection.disconnect() }
                }
            }.start()
        }
        super.onDestroy()
    }

    private fun isLocalAceUrl(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") &&
            uri.port == 6878 && uri.path?.startsWith("/ace/") == true
    }

    companion object {
        const val EXTRA_CONTENT_ID = "com.airtv.receiver.CONTENT_ID"
    }
}
