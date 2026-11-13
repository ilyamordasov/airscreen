package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
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
        minHeight = dp(activity, 58)
        minWidth = 0
        setPadding(dp(activity, 20), 0, dp(activity, 20), 0)
        stateListAnimator = null
    }

    fun panel(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 24))
    }

    fun installRail(activity: Activity, rail: LinearLayout, selected: Section, mainManaged: Boolean = false) {
        rail.removeAllViews()
        rail.orientation = LinearLayout.VERTICAL
        rail.setBackgroundResource(R.drawable.bg_rail)
        rail.setPadding(dp(activity, 16), dp(activity, 28), dp(activity, 16), dp(activity, 22))
        rail.addView(label(activity, "FreeCast", 24f).apply { setTextColor(activity.getColor(R.color.accent)) })
        rail.addView(label(activity, "TV  •  ПУЛЬТ", 11f, true), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(activity, 2); bottomMargin = dp(activity, 32)
        })

        val entries = listOf(
            Triple(Section.HOME, R.id.nav_home, "⌂  Главная"),
            Triple(Section.BROADCASTS, R.id.broadcasts_button, "▣  Трансляции"),
            Triple(Section.VPN, R.id.vpn_button, "◇  VPN"),
            Triple(Section.UPDATES, R.id.update_button, "↓  Обновления"),
            Triple(Section.SETTINGS, R.id.settings_button, "⚙  Настройки"),
        )
        for ((section, id, title) in entries) {
            val button = action(activity, title).apply {
                this.id = id
                setBackgroundResource(R.drawable.bg_nav)
                setTextColor(activity.getColorStateList(R.color.nav_text))
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(activity, 15), 0, dp(activity, 6), 0)
                isSelected = section == selected
                if (!mainManaged) setOnClickListener { navigate(activity, section, selected) }
            }
            rail.addView(button, LinearLayout.LayoutParams(-1, dp(activity, 56)).apply { bottomMargin = dp(activity, 10) })
        }
        val diagnostics = action(activity, "◎  Диагностика").apply {
            id = R.id.diagnostics_button
            setBackgroundResource(R.drawable.bg_nav)
            setTextColor(activity.getColorStateList(R.color.nav_text))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(activity, 15), 0, dp(activity, 6), 0)
            if (!mainManaged) setOnClickListener { openMainPanel(activity, MainActivity.ACTION_OPEN_DIAGNOSTICS) }
        }
        rail.addView(diagnostics, LinearLayout.LayoutParams(-1, dp(activity, 56)))
        rail.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        val bottom = action(activity, if (mainManaged) "⏻  Выход" else "←  Назад").apply {
            id = R.id.exit_button
            setBackgroundResource(R.drawable.bg_nav)
            setTextColor(activity.getColorStateList(R.color.nav_text))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(activity, 15), 0, dp(activity, 6), 0)
            if (!mainManaged) setOnClickListener { activity.finish() }
        }
        rail.addView(bottom, LinearLayout.LayoutParams(-1, dp(activity, 54)))
    }

    fun page(activity: Activity, section: Section, eyebrow: String, title: String, subtitle: String, content: View): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_dashboard)
        }
        val rail = LinearLayout(activity).apply { id = R.id.nav_rail }
        root.addView(rail, LinearLayout.LayoutParams(dp(activity, 192), -1))
        installRail(activity, rail, section)
        val main = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 38), dp(activity, 30), dp(activity, 42), dp(activity, 28))
        }
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        main.addView(label(activity, eyebrow.uppercase(), 13f).apply {
            setTextColor(activity.getColor(R.color.accent)); letterSpacing = 0.13f
        })
        main.addView(label(activity, title, 39f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 6) })
        main.addView(label(activity, subtitle, 15f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 4) })
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(activity, 24) })
        return root
    }

    private fun navigate(activity: Activity, target: Section, selected: Section) {
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
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (action != null) this.action = action
        })
        activity.finish()
    }
}
