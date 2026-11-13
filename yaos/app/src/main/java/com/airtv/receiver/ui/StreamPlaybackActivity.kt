package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.airtv.receiver.link.MediaCandidate
import com.airtv.receiver.link.SharedPage

/** TV-owned HLS/MP4 player with remote-friendly Media3 controls. */
class StreamPlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var openPage: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)?.let(MediaCandidate::parse)
        val pageUrl = intent.getStringExtra(EXTRA_SOURCE_PAGE)?.let(SharedPage::parse)
        if (mediaUrl == null) { finish(); return }

        playerView = PlayerView(this).apply {
            useController = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        status = TextView(this).apply {
            text = "Подключаемся к видеопотоку…"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 18, 24, 18)
        }
        openPage = Button(this).apply {
            text = "Открыть страницу"
            visibility = View.GONE
            setOnClickListener {
                if (pageUrl != null) {
                    startActivity(Intent(this@StreamPlaybackActivity, BroadcastWebActivity::class.java)
                        .putExtra(BroadcastWebActivity.EXTRA_URL, pageUrl))
                    finish()
                }
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            if (pageUrl != null && pageUrl != mediaUrl) addView(openPage)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        })
        val next = ExoPlayer.Builder(this).build()
        player = next
        playerView.player = next
        next.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { status.visibility = View.GONE }
            override fun onPlayerError(error: PlaybackException) {
                status.visibility = View.VISIBLE
                status.text = "Поток недоступен на ТВ (${error.errorCodeName})."
                if (pageUrl != null && pageUrl != mediaUrl) {
                    openPage.visibility = View.VISIBLE
                    openPage.requestFocus()
                }
            }
        })
        val media = MediaItem.Builder().setUri(mediaUrl).apply {
            if (mediaUrl.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        next.setMediaItem(media)
        next.prepare()
        next.playWhenReady = true
        playerView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (openPage.visibility != View.VISIBLE && playerView.dispatchKeyEvent(event)) true
        else super.dispatchKeyEvent(event)

    override fun onDestroy() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MEDIA_URL = "com.airtv.receiver.MEDIA_URL"
        const val EXTRA_SOURCE_PAGE = "com.airtv.receiver.SOURCE_PAGE"
    }
}
