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
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
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
    private val carousel = TvPosterCarousel(context, scale, size(120), showDots = true, open = open)
    val selectedIndex: Int get() = carousel.selectedKey?.ordinal ?: 0

    init {
        addView(header(), LayoutParams(-1, size(46), Gravity.TOP).apply {
            topMargin = size(22)
            marginStart = size(43)
            marginEnd = size(33)
        })
        addView(carousel, LayoutParams(-1, size(TvPosterCarousel.DESIGN_HEIGHT)).apply { topMargin = size(96) })
        carousel.replace(Destination.entries.map { destination ->
            val description = if (destination == Destination.UPDATES) {
                context.getString(destination.description, BuildConfig.VERSION_NAME)
            } else context.getString(destination.description)
            val tint = when (destination) {
                Destination.RECEIVER, Destination.BROADCASTS -> TvGlassDrawable.PINK
                Destination.VPN -> TvGlassDrawable.BLUE
                else -> TvGlassDrawable.VIOLET
            }
            TvPosterCarousel.Poster(destination, context.getString(destination.title), description,
                artwork = destination.artwork, symbol = destination.symbol, tint = tint,
                heading = context.getString(R.string.dashboard_home).takeIf { destination == Destination.RECEIVER })
        })
        updateNetwork()
    }

    fun focus(index: Int = selectedIndex) {
        carousel.focus(Destination.entries[index.coerceIn(Destination.entries.indices)])
    }

    fun showReceiverState(label: CharSequence, color: Int, busy: Boolean) {
        receiverText.text = label
        receiverText.setTextColor(color)
        receiverDot.background = rounded(color, 8)
        receiverDot.visibility = if (busy) GONE else VISIBLE
        progress.visibility = if (busy) VISIBLE else GONE
    }

    fun showAvailableUpdate(version: String) {
        carousel.setDescription(Destination.UPDATES, context.getString(R.string.dashboard_new_version, version))
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
