package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
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

    fun panel(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), dp(activity, 18))
    }

    fun page(activity: Activity, section: Section?, title: String, subtitle: String?, content: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dashboard)
            setPadding(dp(activity, 43), dp(activity, 22), dp(activity, 43), dp(activity, 24))
            addView(navigation(activity, section), LinearLayout.LayoutParams(-1, dp(activity, 46)))
            addView(label(activity, title, 34f), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(activity, 22)
            })
            if (subtitle != null) addView(label(activity, subtitle, 16f, true))
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(activity, 16) })
        }

    private fun navigation(activity: Activity, selected: Section?) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.dashboard_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(activity, 47), dp(activity, 47)))
        addView(label(activity, activity.getString(R.string.app_name), 22f), LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(activity, 6)
            marginEnd = dp(activity, 22)
        })
        val links = listOf(
            Section.HOME to R.string.dashboard_home,
            Section.BROADCASTS to R.string.dashboard_broadcasts,
            Section.VPN to R.string.dashboard_vpn,
            Section.UPDATES to R.string.dashboard_updates,
            Section.SETTINGS to R.string.dashboard_settings,
            null to R.string.dashboard_diagnostics,
            null to R.string.dashboard_back,
        )
        for ((target, title) in links) {
            val button = action(activity, activity.getString(title)).apply {
                setBackgroundResource(R.drawable.bg_nav)
                setTextColor(activity.getColorStateList(R.color.nav_text))
                isSelected = target != null && target == selected
                setPadding(dp(activity, 9), 0, dp(activity, 9), 0)
                textSize = 14f
                setOnClickListener {
                    when {
                        target != null -> navigate(activity, target, selected)
                        title == R.string.dashboard_back -> activity.finish()
                        else -> openMainPanel(activity, MainActivity.ACTION_OPEN_DIAGNOSTICS)
                    }
                }
            }
            addView(button, LinearLayout.LayoutParams(-2, -1).apply { marginEnd = dp(activity, 4) })
        }
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
