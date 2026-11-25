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
import com.airtv.receiver.link.AcePlaybackSession
import com.airtv.receiver.link.AceSource
import com.airtv.receiver.link.AceStreamEngine
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Plays a stream served by the local Ace Stream engine. */
class AcePlaybackActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var overlay: LinearLayout
    private lateinit var retry: Button
    private var premiumRequired = false
    private val main = Handler(Looper.getMainLooper())
    private var session: AcePlaybackSession? = null
    private var enginePort: Int? = null
    @Volatile private var closed = false
    private val firstFrameTimeout = Runnable {
        video.stopPlayback()
        mediaFailed()
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
            mediaFailed()
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
            if (closed) return@Thread
            runOnUiThread { if (!closed) status.setText(R.string.ace_connecting_source) }
            val response = runCatching { AcePlaybackSession.open(port, source) }.getOrElse { error ->
                val message = when (error) {
                    is AcePlaybackSession.PremiumRequired -> R.string.ace_premium_required
                    is ConnectException -> R.string.ace_engine_connection_lost
                    is SocketTimeoutException -> R.string.ace_source_timeout
                    else -> R.string.ace_source_unavailable
                }
                fail(message, "Source", error)
                return@Thread
            }
            runOnUiThread {
                if (closed) {
                    stop(response)
                    return@runOnUiThread
                }
                enginePort = port
                session = response
                status.setText(R.string.ace_buffering)
                main.postDelayed(firstFrameTimeout, 45_000)
                video.setVideoURI(Uri.parse(response.manifest))
                video.requestFocus()
            }
        }.start()
    }

    private fun mediaFailed() {
        main.removeCallbacks(firstFrameTimeout)
        val port = enginePort ?: return
        endSession()
        Thread {
            val message = if (AceStreamEngine.isReady(port)) R.string.ace_no_video else R.string.ace_engine_connection_lost
            runOnUiThread { if (!closed) showError(message) }
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
        stop(session)
        session = null
        enginePort = null
    }

    private fun stop(session: AcePlaybackSession?) {
        if (session == null) return
        Thread {
            runCatching { session.stop() }.onFailure { Log.d(TAG, "Stop failed (${it.javaClass.simpleName})") }
        }.start()
    }

    companion object {
        private const val TAG = "AcePlayback"
        const val EXTRA_CONTENT_ID = "com.airtv.receiver.CONTENT_ID"
        private const val EXTRA_INFOHASH = "com.airtv.receiver.ACE_INFOHASH"

        internal fun intent(context: Context, source: AceSource) = Intent(context, AcePlaybackActivity::class.java)
            .putExtra(if (source.kind == AceSource.Kind.CONTENT_ID) EXTRA_CONTENT_ID else EXTRA_INFOHASH, source.id)
    }
}
