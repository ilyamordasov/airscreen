package com.airtv.receiver.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // The activity creates the dashboard with its navigation callback.
internal class TvDashboard(context: Context, open: (Destination) -> Unit) : FrameLayout(context) {
    enum class Destination(val title: Int, val description: Int, val artwork: Int = 0, val symbol: String = "") {
        RECEIVER(R.string.dashboard_receiver, R.string.dashboard_receiver_description, R.drawable.dashboard_airplay),
        BROADCASTS(R.string.dashboard_broadcasts, R.string.dashboard_broadcasts_description, R.drawable.dashboard_broadcasts),
        VPN(R.string.dashboard_vpn, R.string.dashboard_vpn_description, R.drawable.dashboard_vpn),
        UPDATES(R.string.dashboard_updates, R.string.dashboard_updates_description, R.drawable.dashboard_updates),
        SETTINGS(R.string.dashboard_settings, R.string.dashboard_settings_description, symbol = "⚙"),
        DIAGNOSTICS(R.string.dashboard_diagnostics, R.string.dashboard_diagnostics_description, symbol = "◎"),
    }

    private val scale = minOf(resources.displayMetrics.widthPixels / 960f, resources.displayMetrics.heightPixels / 540f)
    private fun size(value: Int) = (scale * value).roundToInt()
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
        setPadding(size(120), 0, size(120), 0)
    }
    private val indicators = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val receiverText = text(context.getString(R.string.state_starting), 15, true).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val receiverDot = View(context).apply {
        background = rounded(context.getColor(R.color.dashboard_online), 8)
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val network = HeaderSymbol(context, HeaderIcon.OFFLINE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkChanges = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            post { updateNetwork() }
        }

        override fun onLost(network: Network) {
            post { updateNetwork() }
        }
    }
    private val cards = Destination.entries.map { destination ->
        Tile(context, destination).apply {
            id = generateViewId()
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnClickListener { open(destination) }
        }
    }
    var selectedIndex = 0
        private set

    init {
        addView(header(), LayoutParams(-1, size(46), Gravity.TOP).apply {
            topMargin = size(22)
            marginStart = size(43)
            marginEnd = size(33)
        })
        strip.addView(row, LayoutParams(-2, -1))
        addView(strip, LayoutParams(-1, size(364)).apply { topMargin = size(96) })
        addView(indicators, LayoutParams(-1, size(18)).apply { topMargin = size(459) })
        cards.forEachIndexed { index, card ->
            row.addView(card, LinearLayout.LayoutParams(size(173), size(275)).apply { marginEnd = size(14) })
            card.nextFocusLeftId = cards.getOrNull(index - 1)?.id ?: card.id
            card.nextFocusRightId = cards.getOrNull(index + 1)?.id ?: card.id
            card.nextFocusUpId = card.id
            card.nextFocusDownId = card.id
            card.setOnFocusChangeListener { _, focused -> if (focused) select(index, scroll = true) }
            indicators.addView(View(context), LinearLayout.LayoutParams(size(20), size(5)).apply {
                marginStart = size(3)
                marginEnd = size(3)
            })
        }
        select(0, scroll = false)
        updateNetwork()
    }

    fun focus(index: Int = selectedIndex) {
        val target = index.coerceIn(cards.indices)
        select(target, scroll = true)
        cards[target].requestFocus()
    }

    fun showReceiverState(label: CharSequence, color: Int, busy: Boolean) {
        receiverText.text = label
        receiverText.setTextColor(color)
        receiverDot.background = rounded(color, 8)
        receiverDot.visibility = if (busy) GONE else VISIBLE
        progress.visibility = if (busy) VISIBLE else GONE
    }

    fun showAvailableUpdate(version: String) {
        cards[Destination.UPDATES.ordinal].setDescription(context.getString(R.string.dashboard_new_version, version))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkChanges)
        updateNetwork()
    }

    override fun onDetachedFromWindow() {
        connectivity.unregisterNetworkCallback(networkChanges)
        super.onDetachedFromWindow()
    }

    @Suppress("DEPRECATION")
    fun updateNetwork() {
        val active = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val physical = active?.takeUnless { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            ?: connectivity.allNetworks.mapNotNull(connectivity::getNetworkCapabilities)
                .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
                .maxByOrNull { if (it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0 }
        network.icon = when {
            physical?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> HeaderIcon.ETHERNET
            physical?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> HeaderIcon.WIFI
            active != null || physical != null -> HeaderIcon.CONNECTED
            else -> HeaderIcon.OFFLINE
        }
    }

    private fun select(index: Int, scroll: Boolean) {
        selectedIndex = index
        cards.forEachIndexed { position, card ->
            val expanded = position == index
            val params = card.layoutParams
            params.width = size(if (expanded) 294 else 173)
            params.height = size(if (expanded) 321 else 275)
            card.layoutParams = params
            card.expand(expanded)
            indicators.getChildAt(position).background = rounded(
                context.getColor(if (expanded) R.color.dashboard_indicator else R.color.border), 5,
            )
        }
        if (scroll) row.doOnNextLayout {
            if (selectedIndex == index) strip.smoothScrollTo(cards[index].left - row.paddingLeft, 0)
        }
    }

    private fun header() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply { setImageResource(R.drawable.dashboard_logo) },
            LinearLayout.LayoutParams(size(61), size(55)))
        addView(text(context.getString(R.string.app_name), 24, true),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = size(7) })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(context.getColor(R.color.dashboard_pill), 24)
            setPadding(size(17), 0, size(17), 0)
            addView(receiverDot, LinearLayout.LayoutParams(size(10), size(10)))
            addView(progress, LinearLayout.LayoutParams(size(14), size(14)))
            addView(receiverText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = size(8); marginEnd = size(15) })
            addView(HeaderSymbol(context, HeaderIcon.AIRPLAY).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(size(23), size(23)))
        }, LinearLayout.LayoutParams(-2, -1).apply { marginStart = size(68) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(network, LinearLayout.LayoutParams(size(25), size(25)))
        addView(View(context).apply { setBackgroundColor(context.getColor(R.color.border)) },
            LinearLayout.LayoutParams(size(1).coerceAtLeast(1), size(17)).apply {
                marginStart = size(16)
                marginEnd = size(14)
            })
        addView(TextClock(context).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size(18).toFloat())
            setTextColor(context.getColor(R.color.foreground))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun text(value: CharSequence, fontSize: Int, bold: Boolean = false, muted: Boolean = false) =
        TextView(context).apply {
            text = value
            includeFontPadding = false
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size(fontSize).toFloat())
            setTextColor(context.getColor(if (muted) R.color.muted else R.color.foreground))
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = size(radius).toFloat()
    }

    private inner class Tile(context: Context, private val destination: Destination) : FrameLayout(context) {
        private val tint = when (destination) {
            Destination.RECEIVER, Destination.BROADCASTS -> TvGlassDrawable.PINK
            Destination.VPN -> TvGlassDrawable.BLUE
            else -> TvGlassDrawable.VIOLET
        }
        private val normalGlass = TvGlassDrawable(scale, tint, radiusDp = 19)
        private val focusedGlass = TvGlassDrawable(scale, tint, radiusDp = 26, focused = true)
        private val title = text(context.getString(destination.title), 20, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val description = text("", 14, muted = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        private val heading = text(context.getString(R.string.dashboard_home), 15, muted = true)
        private val arrow = text("→", 24, true).apply {
            background = rounded(context.getColor(R.color.dashboard_arrow), 12)
            gravity = Gravity.CENTER
        }
        private val artwork = if (destination.artwork != 0) {
            ImageView(context).apply {
                setImageResource(destination.artwork)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } else text(destination.symbol, 54, true).apply {
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.accent))
            setShadowLayer(size(16).toFloat(), 0f, 0f, context.getColor(R.color.accent))
        }
        private var expanded = false

        init {
            addView(artwork)
            addView(heading)
            addView(title)
            addView(description)
            addView(arrow)
            val summary = if (destination == Destination.UPDATES) {
                context.getString(destination.description, BuildConfig.VERSION_NAME)
            } else context.getString(destination.description)
            setDescription(summary)
        }

        fun setDescription(value: String) {
            description.text = value
            contentDescription = "${title.text}. $value"
        }

        fun expand(value: Boolean) {
            expanded = value
            val receiver = destination == Destination.RECEIVER
            val gap = if (expanded) 31 else 20
            artwork.layoutParams = LayoutParams(-1, size(if (expanded) 136 else 113)).apply {
                topMargin = size(if (expanded) 38 else 25)
                marginStart = size(gap)
                marginEnd = size(gap)
            }
            heading.visibility = if (receiver && expanded) VISIBLE else GONE
            heading.layoutParams = position(gap, 176)
            title.layoutParams = position(gap, if (expanded) (if (receiver) 198 else 183) else 151)
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size(if (expanded) 24 else 19).toFloat())
            val descriptionTop = when {
                receiver && expanded -> 232
                expanded -> 220
                receiver -> 195
                else -> 184
            }
            description.layoutParams = position(gap, descriptionTop)
            arrow.layoutParams = LayoutParams(size(if (expanded) 36 else 33), size(if (expanded) 36 else 33),
                Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = size(17)
                bottomMargin = size(17)
            }
            background = if (expanded) focusedGlass else normalGlass
        }

        private fun position(gap: Int, top: Int) = LayoutParams(-1, -2).apply {
            marginStart = size(gap)
            marginEnd = size(gap)
            topMargin = size(top)
        }

    }

    private enum class HeaderIcon(val label: Int) {
        WIFI(R.string.dashboard_network_wifi), ETHERNET(R.string.dashboard_network_lan),
        CONNECTED(R.string.dashboard_network_connected), OFFLINE(R.string.dashboard_network_offline),
        AIRPLAY(R.string.dashboard_receiver),
    }

    private class HeaderSymbol(context: Context, initial: HeaderIcon) : View(context) {
        var icon = initial
            set(value) {
                field = value
                contentDescription = context.getString(value.label)
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2.2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        init { icon = initial }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val save = canvas.save()
            canvas.scale(width / 24f, height / 24f)
            paint.color = if (icon == HeaderIcon.OFFLINE) 0xFF777891.toInt() else Color.WHITE
            paint.style = Paint.Style.STROKE
            when (icon) {
                HeaderIcon.WIFI, HeaderIcon.OFFLINE -> {
                    canvas.drawArc(-2f, 5f, 26f, 33f, 227f, 86f, false, paint)
                    canvas.drawArc(3f, 10f, 21f, 28f, 226f, 88f, false, paint)
                    canvas.drawArc(8f, 15f, 16f, 23f, 219f, 102f, false, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(12f, 20f, 1.3f, paint)
                }
                HeaderIcon.CONNECTED -> {
                    canvas.drawCircle(12f, 12f, 8f, paint)
                    canvas.drawOval(8f, 4f, 16f, 20f, paint)
                    canvas.drawLine(4f, 12f, 20f, 12f, paint)
                }
                HeaderIcon.ETHERNET -> {
                    canvas.drawRoundRect(3f, 5f, 21f, 18f, 2f, 2f, paint)
                    canvas.drawLine(7f, 18f, 7f, 21f, paint)
                    canvas.drawLine(17f, 18f, 17f, 21f, paint)
                    for (pin in 8..16 step 4) canvas.drawLine(pin.toFloat(), 8f, pin.toFloat(), 11f, paint)
                }
                HeaderIcon.AIRPLAY -> {
                    canvas.drawArc(3f, 3f, 21f, 21f, 144f, 252f, false, paint)
                    canvas.drawArc(7f, 7f, 17f, 17f, 155f, 230f, false, paint)
                    path.reset()
                    path.moveTo(12f, 14f)
                    path.lineTo(18f, 22f)
                    path.lineTo(6f, 22f)
                    path.close()
                    paint.style = Paint.Style.FILL
                    canvas.drawPath(path, paint)
                }
            }
            canvas.restoreToCount(save)
        }
    }
}
