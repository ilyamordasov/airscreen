package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.airtv.receiver.link.MediaCandidate
import com.airtv.receiver.link.SharedPage
import org.json.JSONTokener
import kotlin.math.roundToInt

/** Finds a public stream, with an in-app page and remote pointer as fallback. */
class BroadcastWebActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var cover: View
    private lateinit var toolbar: LinearLayout
    private lateinit var pointer: TextView
    private lateinit var pointerButton: Button
    private val main = Handler(Looper.getMainLooper())
    private var fullScreenView: View? = null
    private var fullScreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousSystemUi = 0
    private var pageUrl = ""
    private var discovering = false
    private var launched = false
    private var pointerMode = false
    private var pointerX = 0f
    private var pointerY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_URL)?.let(SharedPage::parse) ?: run { finish(); return }
        discovering = intent.getBooleanExtra(EXTRA_DISCOVER_MEDIA, false)
        if (discovering) {
            MediaCandidate.parse(pageUrl)?.let { openStream(it); return }
        }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    if (discovering) MediaCandidate.parse(request.url.toString())?.let { candidate ->
                        main.post { if (!launched && discovering) openStream(candidate) }
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullScreenView != null) { callback.onCustomViewHidden(); return }
                    previousSystemUi = window.decorView.systemUiVisibility
                    fullScreenView = view
                    fullScreenCallback = callback
                    web.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    toolbar.bringToFront()
                    pointer.bringToFront()
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                override fun onHideCustomView() { hideFullScreenVideo() }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        cover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = if (discovering) View.VISIBLE else View.GONE
        }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        toolbar = LinearLayout(this).apply {
            setBackgroundColor(0xDD101827.toInt())
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val status = TextView(this).apply {
            text = if (discovering) "Ищем видеопоток…" else "Страница на ТВ"
            textSize = 19f
            setTextColor(Color.WHITE)
        }
        toolbar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        val showPage = Button(this).apply {
            text = "Открыть страницу"
            visibility = if (discovering) View.VISIBLE else View.GONE
            setOnClickListener {
                discovering = false
                cover.visibility = View.GONE
                visibility = View.GONE
                status.text = "Страница на ТВ"
                pointerButton.visibility = View.VISIBLE
                pointerButton.requestFocus()
            }
        }
        toolbar.addView(showPage)
        pointerButton = Button(this).apply {
            text = "Указатель"
            visibility = if (discovering) View.GONE else View.VISIBLE
            setOnClickListener { setPointerMode(!pointerMode) }
        }
        toolbar.addView(pointerButton)
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        pointer = TextView(this).apply {
            text = "◎"
            textSize = 34f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(pointer, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.LEFT))
        setContentView(root)
        web.loadUrl(pageUrl)
        if (discovering) {
            showPage.requestFocus()
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!discovering || launched || isDestroyed) return
                    web.evaluateJavascript(VIDEO_URL_JS) { result ->
                        val value = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
                        value?.let(MediaCandidate::parse)?.let(::openStream)
                    }
                    main.postDelayed(this, 1_500)
                }
            }, 1_500)
            main.postDelayed({ if (discovering && !launched) status.text = "Поток не найден. Можно открыть страницу." }, 12_000)
        } else pointerButton.requestFocus()
    }

    private fun openStream(url: String) {
        if (launched || isFinishing || isDestroyed) return
        launched = true
        startActivity(Intent(this, StreamPlaybackActivity::class.java)
            .putExtra(StreamPlaybackActivity.EXTRA_MEDIA_URL, url)
            .putExtra(StreamPlaybackActivity.EXTRA_SOURCE_PAGE, pageUrl))
        finish()
    }

    private fun setPointerMode(enabled: Boolean) {
        pointerMode = enabled
        pointerButton.text = if (enabled) "Указатель: вкл. · Back — выход" else "Указатель"
        pointer.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            pointerX = root.width / 2f
            pointerY = root.height / 2f
            movePointer(0f, 0f)
        } else pointerButton.requestFocus()
    }

    private fun movePointer(dx: Float, dy: Float) {
        pointerX = (pointerX + dx).coerceIn(0f, root.width.toFloat())
        pointerY = (pointerY + dy).coerceIn(0f, root.height.toFloat())
        pointer.x = pointerX - pointer.width / 2f
        pointer.y = pointerY - pointer.height / 2f
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!pointerMode) return super.dispatchKeyEvent(event)
        val handled = event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = dp(38).toFloat()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> movePointer(-step, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> movePointer(step, 0f)
                KeyEvent.KEYCODE_DPAD_UP -> movePointer(0f, -step)
                KeyEvent.KEYCODE_DPAD_DOWN -> movePointer(0f, step)
                else -> tapPointer()
            }
        }
        return true
    }

    private fun tapPointer() {
        val target = fullScreenView ?: web
        val downTime = android.os.SystemClock.uptimeMillis()
        val targetLocation = IntArray(2).also { target.getLocationOnScreen(it) }
        val rootLocation = IntArray(2).also { root.getLocationOnScreen(it) }
        val x = pointerX + rootLocation[0] - targetLocation[0]
        val y = pointerY + rootLocation[1] - targetLocation[1]
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Deprecated("Back navigation is handled by the embedded provider page.")
    override fun onBackPressed() {
        when {
            pointerMode -> setPointerMode(false)
            fullScreenView != null -> hideFullScreenVideo()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            hideFullScreenVideo()
            web.destroy()
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_URL = "broadcast_url"
        const val EXTRA_DISCOVER_MEDIA = "discover_media"
        private const val VIDEO_URL_JS = """(function(){for(const v of document.querySelectorAll('video,source')){const u=v.currentSrc||v.src;if(u&&u.startsWith('https://'))return u}return ''})()"""
    }
}
