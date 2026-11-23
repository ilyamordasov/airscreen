package com.airtv.receiver.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.airtv.receiver.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@SuppressLint("ViewConstructor")
internal class TvPairingPanel(
    private val activity: Activity,
    description: String,
    title: String,
    instructions: String,
) : LinearLayout(activity) {
    private val compact = resources.configuration.screenHeightDp < 440
    private val qr = PairingCodeView(activity).apply {
        id = R.id.pairing_qr
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = TvGlassDrawable(resources.displayMetrics.density, TvGlassDrawable.BLUE)
        visibility = GONE
    }
    private val codeValue = TvChrome.label(activity, "", if (compact) 28f else 36f).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = .12f
    }
    private val codeBlock = LinearLayout(activity).apply {
        orientation = if (compact) HORIZONTAL else VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val label = TvChrome.label(activity, activity.getString(R.string.pairing_code), 15f, muted = true)
        addView(label, if (compact) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
        addView(codeValue)
        visibility = GONE
    }
    private val feedback = TvChrome.label(activity, "", 16f, muted = true).apply {
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }
    private val details = LinearLayout(activity).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TvChrome.label(activity, title, if (compact) 21f else 26f))
        addView(TvChrome.label(activity, instructions, if (compact) 15f else 18f, muted = true), row(8))
        addView(codeBlock, row(if (compact) 8 else 16))
        addView(feedback, row(8))
    }
    private val card = TvChrome.panel(activity).apply {
        if (compact) setPadding(dp(20), dp(12), dp(20), dp(12))
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(details, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
        addView(qr, LayoutParams(0, -1, .9f))
        addView(card, LayoutParams(0, -1, 1.1f))
    }

    fun showCode(url: String, code: String) {
        qr.setUrl(url)
        qr.visibility = VISIBLE
        codeBlock.visibility = VISIBLE
        codeValue.text = code
        card.layoutParams = (card.layoutParams as LayoutParams).apply { marginStart = dp(20) }
    }

    fun showFeedback(message: CharSequence?) {
        feedback.text = message
        feedback.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
        if (feedback.isShown) feedback.post {
            feedback.requestRectangleOnScreen(Rect(0, 0, feedback.width, feedback.height), false)
        }
    }

    fun setAction(view: View) {
        card.addView(view, LayoutParams(-1, if (compact) dp(42) else -2).apply { topMargin = dp(8) })
    }

    fun addDetail(view: View) {
        details.addView(view, row(12))
    }

    private fun dp(value: Int) = TvChrome.dp(activity, value)

    private fun row(top: Int) = LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private class PairingCodeView(activity: Activity) : View(activity) {
        private val inset = TvChrome.dp(activity, 20)
        private val maxSide = TvChrome.dp(activity, 320)
        private val target = Rect()
        private val paint = Paint().apply { isFilterBitmap = false }
        private var bitmap: Bitmap? = null

        fun setUrl(url: String) {
            // ZXing's default quiet zone stays part of the bitmap. Scale whole modules to device pixels.
            val modules = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0)
            val pixels = IntArray(modules.width * modules.height) { i ->
                if (modules[i % modules.width, i / modules.width]) Color.BLACK else Color.WHITE
            }
            bitmap = Bitmap.createBitmap(pixels, modules.width, modules.height, Bitmap.Config.ARGB_8888)
            updateTarget()
            invalidate()
        }

        private fun updateTarget() {
            val modules = bitmap?.width ?: return
            val available = (minOf(width, height) - 2 * inset).coerceIn(0, maxSide)
            val side = available / modules * modules
            val left = (width - side) / 2
            val top = (height - side) / 2
            target.set(left, top, left + side, top + side)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateTarget()
        }

        override fun onDraw(canvas: Canvas) {
            bitmap?.let { if (!target.isEmpty) canvas.drawBitmap(it, null, target, paint) }
        }
    }
}
