package com.airtv.receiver.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import com.airtv.receiver.link.AceCatalogue
import com.airtv.receiver.link.AceSource
import com.airtv.receiver.link.AceStreamEngine
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

/** Plays a stream served by the local Ace Stream engine. */
class AcePlaybackActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var overlay: LinearLayout
    private lateinit var retry: Button
    private var premiumRequired = false
    private val main = Handler(Looper.getMainLooper())
    private var stopUrl: String? = null
    private var closed = false
    private val firstFrameTimeout = Runnable {
        video.stopPlayback()
        endSession()
        showError(R.string.ace_no_video)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = AceSource.parse(AceSource.Kind.CONTENT_ID, intent.getStringExtra(EXTRA_CONTENT_ID))
            ?: AceSource.parse(AceSource.Kind.INFOHASH, intent.getStringExtra(EXTRA_INFOHASH))
        if (source == null) {
            finish()
            return
        }
        video = VideoView(this)
        status = TvChrome.label(this, getString(R.string.p2p_engine_loading), 20f)
        retry = TvChrome.action(this, getString(R.string.ace_retry_playback), true).apply {
            visibility = View.GONE
            setOnClickListener { if (premiumRequired) finish() else play(source) }
        }
        val inset = TvChrome.dp(this, 24)
        overlay = TvChrome.panel(this).apply {
            addView(status)
            addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = TvChrome.dp(this@AcePlaybackActivity, 12) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(this@AcePlaybackActivity.overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(inset, inset, inset, inset)
            })
        })
        video.setOnPreparedListener { prepared ->
            prepared.isLooping = false
            status.setText(R.string.ace_buffering)
            video.start()
        }
        video.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                main.removeCallbacks(firstFrameTimeout)
                overlay.visibility = View.GONE
            }
            false
        }
        video.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "VideoView failed ($what/$extra)")
            endSession()
            showError(R.string.ace_no_video)
            true
        }
        play(source)
    }

    private fun play(source: AceSource) {
        overlay.visibility = View.VISIBLE
        retry.visibility = View.GONE
        status.setText(R.string.p2p_engine_loading)
        Thread {
            val port = runCatching { AceStreamEngine.port(this) }.getOrElse { error ->
                fail(if (BuildConfig.EMBEDDED_ACE) R.string.p2p_engine_unavailable else R.string.p2p_external_engine_unavailable,
                    "Engine", error)
                return@Thread
            }
            runOnUiThread { if (!closed) status.setText(R.string.ace_connecting_source) }
            val response = runCatching {
                val request = AceStreamEngine.connection(port, "/ace/manifest.m3u8?format=json&${source.parameter}").apply {
                    connectTimeout = 4_000
                    readTimeout = 40_000
                }
                val reply = JSONObject(AceCatalogue.read(request))
                val error = reply.opt("error")
                if (error != null && error != JSONObject.NULL) {
                    if (error.toString().contains("premium", ignoreCase = true)) throw PremiumRequired()
                    throw IOException("Engine rejected the source")
                }
                val playback = reply.getJSONObject("response")
                val manifest = playback.getString("playback_url")
                val command = playback.optString("command_url").takeIf { it.isNotEmpty() }
                require(isLocalAceUrl(manifest, port))
                require(command == null || isLocalAceUrl(command, port))
                manifest to command
            }.getOrElse { error ->
                val message = when (error) {
                    is PremiumRequired -> R.string.ace_premium_required
                    is SocketTimeoutException -> R.string.ace_source_timeout
                    else -> R.string.ace_source_unavailable
                }
                fail(message, "Source", error)
                return@Thread
            }
            runOnUiThread {
                if (closed) {
                    stop(response.second)
                    return@runOnUiThread
                }
                stopUrl = response.second
                status.setText(R.string.ace_buffering)
                main.postDelayed(firstFrameTimeout, 45_000)
                video.setVideoURI(Uri.parse(response.first))
                video.requestFocus()
            }
        }.start()
    }

    private fun fail(message: Int, stage: String, error: Throwable) {
        Log.w(TAG, "$stage failed (${error.javaClass.simpleName})")
        runOnUiThread { if (!closed) showError(message) }
    }

    private fun showError(message: Int) {
        main.removeCallbacks(firstFrameTimeout)
        overlay.visibility = View.VISIBLE
        status.setText(message)
        premiumRequired = message == R.string.ace_premium_required
        retry.setText(if (premiumRequired) R.string.ace_choose_source else R.string.ace_retry_playback)
        retry.visibility = View.VISIBLE
        retry.requestFocus()
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacks(firstFrameTimeout)
        if (::video.isInitialized) video.stopPlayback()
        endSession()
        super.onDestroy()
    }

    private fun endSession() {
        stop(stopUrl)
        stopUrl = null
    }

    private fun stop(url: String?) {
        if (url == null) return
        Thread {
            runCatching {
                val request = (URL("$url${if ('?' in url) '&' else '?'}method=stop")
                    .openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 2_000
                    readTimeout = 2_000
                }
                AceCatalogue.read(request)
            }
        }.start()
    }

    private fun isLocalAceUrl(value: String, port: Int): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") &&
            uri.port == port && uri.path?.startsWith("/ace/") == true
    }

    private class PremiumRequired : IOException()

    companion object {
        private const val TAG = "AcePlayback"
        const val EXTRA_CONTENT_ID = "com.airtv.receiver.CONTENT_ID"
        private const val EXTRA_INFOHASH = "com.airtv.receiver.ACE_INFOHASH"

        internal fun intent(context: Context, source: AceSource) = Intent(context, AcePlaybackActivity::class.java)
            .putExtra(if (source.kind == AceSource.Kind.CONTENT_ID) EXTRA_CONTENT_ID else EXTRA_INFOHASH, source.id)
    }
}
