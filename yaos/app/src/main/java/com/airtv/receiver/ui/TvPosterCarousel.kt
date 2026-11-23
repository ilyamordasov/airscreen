package com.airtv.receiver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import com.airtv.receiver.R
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class TvPosterCarousel<Key : Any>(
    context: Context,
    private val maxScale: Float,
    private val sideInset: Int,
    private val showDots: Boolean,
    private val focusUpId: Int = View.NO_ID,
    private val open: (Key) -> Unit,
) : FrameLayout(context) {
    data class Poster<Key : Any>(
        val key: Key,
        val title: String,
        val description: String,
        val artwork: Int = 0,
        val symbol: String = "",
        val tint: Int = TvGlassDrawable.VIOLET,
        val heading: String? = null,
        val actionSymbol: String = "→",
    )

    private var scale = maxScale
    private val strip = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }
    private val row = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val dots = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val count = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(context.getColor(R.color.muted))
    }
    private val cards = mutableListOf<Tile>()
    var selectedKey: Key? = null
        private set
    var onSelectionChanged: ((Int) -> Unit)? = null

    init {
        strip.addView(row, LayoutParams(-2, -1))
        addView(strip, LayoutParams(-1, -1))
        addView(if (showDots) dots else count, LayoutParams(-1, size(18), Gravity.BOTTOM))
        resize()
    }

    fun replace(items: List<Poster<Key>>, preferred: Key? = selectedKey) {
        val restoreFocus = hasFocus()
        row.removeAllViews()
        dots.removeAllViews()
        cards.clear()
        selectedKey = null
        items.forEach(::append)
        val target = cards.firstOrNull { it.key == preferred } ?: cards.firstOrNull() ?: return
        if (restoreFocus) focus(target.key) else select(target)
    }

    fun append(poster: Poster<Key>) {
        if (cards.any { it.key == poster.key }) return
        val card = Tile(context, poster).apply {
            id = generateViewId()
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            nextFocusDownId = id
            nextFocusUpId = if (showDots) id else focusUpId
            nextFocusRightId = id
            nextFocusLeftId = cards.lastOrNull()?.id ?: id
            setOnClickListener { open(key) }
            setOnFocusChangeListener { _, focused -> if (focused) select(this) }
        }
        cards.lastOrNull()?.nextFocusRightId = card.id
        cards.add(card)
        row.addView(card, LinearLayout.LayoutParams(0, 0))
        if (showDots) dots.addView(View(context), LinearLayout.LayoutParams(0, 0))
        if (selectedKey == null) selectedKey = card.key
        card.resize(card.key == selectedKey)
        updateIndicator()
    }

    fun focus(key: Key? = selectedKey) {
        val card = cards.firstOrNull { it.key == key } ?: cards.firstOrNull() ?: return
        select(card)
        card.requestFocus()
    }

    fun setDescription(key: Key, description: String) {
        cards.firstOrNull { it.key == key }?.setDescription(description)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (height > 0) {
            val fit = minOf(maxScale, height / DESIGN_HEIGHT.toFloat())
            if (fit != scale) {
                scale = fit
                resize()
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun size(units: Int) = (scale * units).roundToInt().coerceAtLeast(1)

    private fun select(card: Tile) {
        val old = selectedKey
        selectedKey = card.key
        if (old != card.key) {
            cards.firstOrNull { it.key == old }?.resize(false)
            card.resize(true)
        }
        updateIndicator()
        onSelectionChanged?.invoke(card.id)
        if (row.isLayoutRequested || !row.isLaidOut) row.doOnNextLayout { align(card) } else align(card)
    }

    private fun align(card: Tile) {
        if (selectedKey == card.key) strip.smoothScrollTo(card.left - row.paddingLeft, 0)
    }

    private fun resize() {
        val markerHeight = size(18)
        (strip.layoutParams as LayoutParams).bottomMargin = markerHeight
        (if (showDots) dots else count).layoutParams.height = markerHeight
        row.setPadding(sideInset, 0, sideInset, 0)
        cards.forEach { it.resize(it.key == selectedKey) }
        cards.firstOrNull { it.key == selectedKey }?.let { target ->
            row.doOnNextLayout { align(target) }
        }
        updateIndicator()
    }

    private fun updateIndicator() {
        if (showDots) {
            cards.forEachIndexed { index, card ->
                dots.getChildAt(index).apply {
                    layoutParams = LinearLayout.LayoutParams(size(20), size(5)).apply {
                        marginStart = size(3)
                        marginEnd = size(3)
                    }
                    background = rounded(context.getColor(
                        if (card.key == selectedKey) R.color.dashboard_indicator else R.color.border), size(5))
                }
            }
        } else {
            count.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(15).toFloat())
            val index = cards.indexOfFirst { it.key == selectedKey } + 1
            count.text = if (index == 0) "" else context.getString(R.string.broadcast_position, index, cards.size)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private inner class Tile(context: Context, private val poster: Poster<Key>) : FrameLayout(context) {
        val key: Key get() = poster.key
        private var lastScale = 0f
        private var active = false
        private val title = text(poster.title, bold = true).apply { maxLines = 2 }
        private val description = text(poster.description, muted = true).apply { maxLines = 3 }
        private val heading = text(poster.heading.orEmpty(), muted = true)
        private val arrow = text(poster.actionSymbol, bold = true).apply { gravity = Gravity.CENTER }
        private val artwork = if (poster.artwork != 0) {
            ImageView(context).apply {
                setImageResource(poster.artwork)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        } else text(poster.symbol, bold = true).apply {
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
        }
        private val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(heading)
            addView(title)
            addView(description)
        }

        init {
            addView(artwork)
            addView(copy)
            addView(arrow)
            setDescription(poster.description)
        }

        fun setDescription(value: String) {
            description.text = value
            contentDescription = "${poster.title}. $value"
        }

        fun resize(expanded: Boolean) {
            if (lastScale == scale && active == expanded) return
            lastScale = scale
            active = expanded
            layoutParams = LinearLayout.LayoutParams(size(if (expanded) 294 else 173), size(if (expanded) 321 else 275)).apply {
                marginEnd = size(14)
            }
            val gap = size(if (expanded) 31 else 20)
            artwork.layoutParams = LayoutParams(-1, size(if (expanded) 136 else 113)).apply {
                topMargin = size(if (expanded) 38 else 25)
                marginStart = gap
                marginEnd = gap
            }
            if (artwork is TextView) {
                artwork.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(54).toFloat())
                artwork.setShadowLayer(size(16).toFloat(), 0f, 0f, context.getColor(R.color.accent))
            }
            val showHeading = expanded && poster.heading != null
            heading.visibility = if (showHeading) VISIBLE else GONE
            heading.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(15).toFloat())
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(if (expanded) 24 else 19).toFloat())
            description.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(14).toFloat())
            (title.layoutParams as LinearLayout.LayoutParams).topMargin = if (showHeading) size(5) else 0
            (description.layoutParams as LinearLayout.LayoutParams).topMargin = size(if (expanded) 9 else 7)
            copy.layoutParams = LayoutParams(-1, -2).apply {
                marginStart = gap
                marginEnd = gap
                topMargin = size(when { showHeading -> 176; expanded -> 183; else -> 151 })
            }
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_PX, size(24).toFloat())
            arrow.background = rounded(context.getColor(R.color.dashboard_arrow), size(12))
            val side = size(if (expanded) 36 else 33)
            arrow.layoutParams = LayoutParams(side, side, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = size(17)
                bottomMargin = size(17)
            }
            background = TvGlassDrawable(scale, poster.tint, if (expanded) 26 else 19, focused = expanded)
        }

        private fun text(value: String, bold: Boolean = false, muted: Boolean = false) = TextView(context).apply {
            text = value
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(context.getColor(if (muted) R.color.muted else R.color.foreground))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    companion object {
        const val DESIGN_HEIGHT = 382
    }
}
