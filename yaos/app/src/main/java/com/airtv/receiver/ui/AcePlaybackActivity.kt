package com.airtv.receiver.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import com.airtv.receiver.link.AceStreamEngine
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
        status = TvChrome.label(this, "Подключаемся к Ace Stream Engine…", 20f).apply {
            val inset = TvChrome.dp(this@AcePlaybackActivity, 16)
            setPadding(inset, inset, inset, inset)
            setBackgroundResource(R.drawable.bg_floating)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            val inset = TvChrome.dp(this@AcePlaybackActivity, 32)
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = inset
                marginStart = inset
                marginEnd = inset
            })
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
                val port = AceStreamEngine.port(this)
                val connection = AceStreamEngine.connection(port, "/ace/manifest.m3u8?format=json&content_id=$contentId").apply {
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
                    require(isLocalAceUrl(streamUrl, port)) { "Ace Engine вернул посторонний адрес видео" }
                    require(commandUrl.isEmpty() || isLocalAceUrl(commandUrl, port)) {
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
                    status.text = if (BuildConfig.EMBEDDED_ACE) getString(R.string.ace_embedded_unavailable)
                        else "Не удалось открыть эфир в AirScreen. Запустите Ace Stream Engine: ${it.localizedMessage.orEmpty()}"
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

    private fun isLocalAceUrl(value: String, port: Int): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") &&
            uri.port == port && uri.path?.startsWith("/ace/") == true
    }

    companion object {
        const val EXTRA_CONTENT_ID = "com.airtv.receiver.CONTENT_ID"
    }
}
