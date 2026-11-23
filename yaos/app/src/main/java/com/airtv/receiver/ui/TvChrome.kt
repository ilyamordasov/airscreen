package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.airtv.receiver.R
import kotlin.math.roundToInt

/** Shared 10-foot navigation and visual primitives for the TV-only screens. */
object TvChrome {
    enum class Section { HOME, BROADCASTS, VPN, UPDATES, SETTINGS }

    fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).roundToInt()

    fun label(activity: Activity, text: String, size: Float, muted: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(activity.getColor(if (muted) R.color.muted else R.color.foreground))
        typeface = Typeface.create("sans-serif", if (size >= 24f) Typeface.BOLD else Typeface.NORMAL)
    }

    fun action(activity: Activity, text: String, primary: Boolean = false): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(activity.getColorStateList(R.color.button_text))
        setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button)
        minHeight = dp(activity, 46)
        minWidth = 0
        setPadding(dp(activity, 20), 0, dp(activity, 20), 0)
        stateListAnimator = null
    }

    internal fun glassTile(activity: Activity, text: String, tint: Int = TvGlassDrawable.VIOLET): Button =
        action(activity, text).apply {
            val density = activity.resources.displayMetrics.density
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), TvGlassDrawable(density, tint, 18, focused = true))
                addState(intArrayOf(android.R.attr.state_pressed), TvGlassDrawable(density, tint, 18, focused = true))
                addState(intArrayOf(), TvGlassDrawable(density, tint, 18))
            }
            setTextColor(activity.getColor(R.color.foreground))
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }

    fun panel(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = TvGlassDrawable(activity.resources.displayMetrics.density)
        setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), dp(activity, 18))
    }

    fun page(
        activity: Activity,
        section: Section?,
        title: String,
        subtitle: String?,
        content: View,
        titleActions: View? = null,
        onBack: () -> Unit = activity::finish,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_dashboard)
        val compact = resources.configuration.screenHeightDp < 440
        val side = dp(activity, if (resources.configuration.screenWidthDp < 800) 24 else 43)
        setPadding(side, dp(activity, if (compact) 16 else 22), side, dp(activity, if (compact) 16 else 24))
        addView(navigation(activity, section, onBack), LinearLayout.LayoutParams(-1, dp(activity, 46)))
        val heading = label(activity, title, if (compact) 28f else 34f)
        val headingRow = if (titleActions == null) heading else LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            addView(titleActions, LinearLayout.LayoutParams(-2, -2))
        }
        addView(headingRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(activity, if (compact) 12 else 22)
        })
        if (subtitle != null) addView(label(activity, subtitle, 16f, true))
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(activity, 16) })
    }

    private fun navigation(activity: Activity, selected: Section?, onBack: () -> Unit) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.dashboard_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(activity, 47), dp(activity, 47)))
        addView(label(activity, activity.getString(R.string.app_name), 22f), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(activity, 6)
            marginEnd = dp(activity, 16)
        })
        val allLinks = listOf(
            Section.HOME to R.string.dashboard_home,
            Section.BROADCASTS to R.string.dashboard_broadcasts,
            Section.VPN to R.string.dashboard_vpn,
            Section.UPDATES to R.string.dashboard_updates,
            Section.SETTINGS to R.string.dashboard_settings,
            null to R.string.dashboard_diagnostics,
        )
        val links = if (resources.configuration.screenWidthDp < 800) allLinks.take(1) else allLinks
        val navRow = LinearLayout(activity).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            for ((target, title) in links) {
                addView(navAction(activity, title).apply {
                    isSelected = target != null && target == selected
                    setOnClickListener {
                        if (target == null) openMainPanel(activity, MainActivity.ACTION_OPEN_DIAGNOSTICS)
                        else navigate(activity, target, selected)
                    }
                }, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(activity, 4) })
            }
        }
        addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(navRow, android.widget.FrameLayout.LayoutParams(-2, -1))
        }, LinearLayout.LayoutParams(0, -1, 1f))
        addView(navAction(activity, R.string.dashboard_back).apply { setOnClickListener { onBack() } },
            LinearLayout.LayoutParams(-2, -1))
    }

    private fun navAction(activity: Activity, title: Int) = action(activity, activity.getString(title)).apply {
        setBackgroundResource(R.drawable.bg_nav)
        setTextColor(activity.getColorStateList(R.color.nav_text))
        setPadding(dp(activity, 9), 0, dp(activity, 9), 0)
        textSize = 14f
    }

    private fun navigate(activity: Activity, target: Section, selected: Section?) {
        if (target == selected) return
        when (target) {
            Section.HOME -> openMainPanel(activity, null)
            Section.SETTINGS -> openMainPanel(activity, MainActivity.ACTION_OPEN_SETTINGS)
            else -> {
                val cls = when (target) {
                    Section.BROADCASTS -> BroadcastsActivity::class.java
                    Section.VPN -> VpnActivity::class.java
                    Section.UPDATES -> UpdateActivity::class.java
                    else -> MainActivity::class.java
                }
                activity.startActivity(Intent(activity, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                activity.finish()
            }
        }
    }

    private fun openMainPanel(activity: Activity, action: String?) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action != null) this.action = action
        })
        activity.finish()
    }
}
