package com.airtv.receiver.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/** Fallback for TVs without a browser app; provider login and playback stay on the TV. */
class BroadcastWebActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(web)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null || !url.startsWith("https://matchtv.ru/")) {
            finish()
            return
        }
        web.loadUrl(url)
    }

    @Deprecated("Back navigation is handled by the embedded provider page.")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "broadcast_url" }
}
