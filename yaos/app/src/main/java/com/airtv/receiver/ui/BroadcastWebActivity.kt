package com.airtv.receiver.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.airtv.receiver.link.SharedPage

/** Provider page playback stays inside FreeCast TV and is fetched by the TV. */
class BroadcastWebActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUi = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    previousSystemUi = window.decorView.systemUiVisibility
                    fullScreenView = view
                    fullScreenCallback = callback
                    web.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }

                override fun onHideCustomView() { hideFullScreenVideo() }
            }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val url = intent.getStringExtra(EXTRA_URL)
        val page = url?.let(SharedPage::parse)
        if (page == null) {
            finish()
            return
        }
        web.loadUrl(page)
    }

    @Deprecated("Back navigation is handled by the embedded provider page.")
    override fun onBackPressed() {
        when {
            fullScreenView != null -> hideFullScreenVideo()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        hideFullScreenVideo()
        web.destroy()
        super.onDestroy()
    }

    private fun hideFullScreenVideo() {
        val view = fullScreenView ?: return
        fullScreenView = null
        root.removeView(view)
        web.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = previousSystemUi
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }

    companion object { const val EXTRA_URL = "broadcast_url" }
}
