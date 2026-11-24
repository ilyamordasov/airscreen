package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.airtv.receiver.FreeCastApplication
import com.airtv.receiver.R
import com.airtv.receiver.vpn.CheckedProxy
import com.airtv.receiver.vpn.FreeCastVpnService
import com.airtv.receiver.vpn.ProxyScanner
import com.airtv.receiver.vpn.VlessProfile
import com.airtv.receiver.vpn.VpnImportServer
import com.airtv.receiver.vpn.VpnProfileStore
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/** Import from a phone, then connect only FreeCast to the VLESS tunnel. */
class VpnActivity : Activity() {
    private var server: VpnImportServer? = null
    private lateinit var status: TextView
    private lateinit var profileStatus: TextView
    private lateinit var importStatus: TextView
    private lateinit var proxyRows: LinearLayout
    private lateinit var overview: LinearLayout
    private lateinit var importPanel: TvPairingPanel
    private lateinit var importAction: Button
    private lateinit var closeImport: Button
    private lateinit var deleteAction: Button
    private lateinit var actions: LinearLayout
    private val busyActions = mutableListOf<Button>()
    private var manualStops = 0
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false
    private val refresh = object : Runnable {
        override fun run() {
            status.text = FreeCastVpnService.state
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        profileStatus = TvChrome.label(this, profileLabel(), 17f, muted = true)
        status = TvChrome.label(this, FreeCastVpnService.state, 21f)
        importStatus = TvChrome.label(this, "", 16f).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            doAfterTextChanged { value ->
                visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
                if (::importPanel.isInitialized && importPanel.isShown) importPanel.showFeedback(value)
            }
        }
        val info = TvChrome.panel(this)
        info.addView(TvChrome.label(this, "VPN ТОЛЬКО ДЛЯ AIRSCREEN", 13f).apply { setTextColor(getColor(R.color.accent)) })
        info.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(profileStatus, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        })
        info.addView(importStatus)
        overview.addView(info, LinearLayout.LayoutParams(-1, -2))
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        overview.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addAction(actions, "Подключить", primary = true) { connect() }
        addAction(actions, getString(R.string.vpn_stop_core), allowWhileBusy = true) { stopVpn() }
        addAction(actions, getString(R.string.vpn_restart_core)) { connect(restart = true) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        overview.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addAction(tools, "Обновить прокси", action = ::updateProxies)
        importAction = addAction(tools, "Импорт по QR") { showImport(true) }
        proxyRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        overview.addView(ScrollView(this).apply { addView(proxyRows) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        deleteAction = TvChrome.action(this, "Удалить профиль").apply {
            setOnClickListener { if (!busy) deleteProfile() }
        }
        overview.addView(deleteAction, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        importPanel = TvPairingPanel(this, getString(R.string.dashboard_vpn_qr),
            getString(R.string.pairing_vpn_title), getString(R.string.pairing_vpn_instructions))
        closeImport = TvChrome.action(this, getString(R.string.pairing_vpn_back)).apply {
            setOnClickListener { showImport(false) }
        }
        importPanel.setAction(closeImport)
        val content = FrameLayout(this).apply {
            addView(overview, FrameLayout.LayoutParams(-1, -1))
            addView(importPanel, FrameLayout.LayoutParams(-1, -1))
        }
        renderProxies(VpnProfileStore.proxies(this))
        setContentView(TvChrome.page(this, TvChrome.Section.VPN, "VPN", null, content, onBack = ::goBack))
        val showPairing = savedInstanceState?.getBoolean(STATE_IMPORT_OPEN) ?: (VpnProfileStore.load(this) == null)
        showImport(showPairing)
        if (!showPairing) actions.getChildAt(0).requestFocus()

        val address = LanPairingAddress.find(this)
        if (address == null) {
            importPanel.showFeedback(getString(R.string.pairing_network_missing))
            return
        }
        val token = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        val url = "http://$address:${VpnImportServer.PORT}/"
        try {
            server = VpnImportServer(token) { value -> import(value) }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            importPanel.showCode(url, token)
        } catch (_: Exception) {
            server?.stop()
            server = null
            importPanel.showFeedback("Не удалось открыть страницу импорта на ТВ.")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_IMPORT_OPEN, importPanel.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Android TV hardware Back closes the pairing view first.")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (importPanel.visibility == View.VISIBLE) showImport(false) else finish()
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() {
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun import(value: String) = runOnUiThread {
        if (isDestroyed || busy) return@runOnUiThread
        setBusy(true)
        importStatus.text = "Загружаем профиль…"
        worker.execute {
            try {
                val source = value.takeUnless { it.startsWith("vless://") }
                val profile = if (source == null) VlessProfile.parse(value) else downloadProfiles(source).first()
                runOnUiThread {
                    if (!isDestroyed) {
                        runCatching { VpnProfileStore.saveImport(this, profile, source) }.onSuccess {
                            stopVpn()
                            profileStatus.text = profileLabel()
                            renderProxies(emptyList())
                            importStatus.text = if (source == null) "Профиль сохранён."
                                else "Подписка сохранена. Обновите список прокси."
                            if (importPanel.visibility == View.VISIBLE) showImport(false)
                        }.onFailure { importStatus.text = "Не удалось сохранить профиль." }
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) importStatus.text = when (error.message) {
                        "Подписка не содержит VLESS-ссылок", "Подписка не содержит совместимых профилей VLESS" ->
                            "Подписка не содержит совместимых профилей VLESS."
                        "Подписка слишком большая" -> "Подписка слишком большая."
                        else -> "Не удалось импортировать профиль. Проверьте ссылку и формат."
                    }
                }
            } finally {
                runOnUiThread { if (!isDestroyed) setBusy(false) }
            }
        }
    }

    private fun updateProxies() {
        if (busy) return
        val source = VpnProfileStore.subscription(this)
        if (source == null) {
            showImport(true)
            return
        }
        val restoreVpn = isVpnRunning()
        val app = application as FreeCastApplication
        val session = app.foregroundSession
        val stopCount = manualStops
        if (restoreVpn) FreeCastVpnService.disconnect(this)
        setBusy(true)
        importStatus.text = "Обновляем подписку…"
        worker.execute {
            val result = runCatching {
                waitForVpnToStop()
                val profiles = downloadProfiles(source)
                ProxyScanner(applicationContext).scan(profiles) { checked, total ->
                    handler.post {
                        if (!isDestroyed) importStatus.text = "Проверяем доступ к TVCrate: $checked из $total"
                    }
                }
            }
            handler.post {
                if (!isDestroyed) {
                    result.mapCatching { ranked ->
                        VpnProfileStore.saveProxies(this, ranked)
                        ranked
                    }.onSuccess { ranked ->
                        profileStatus.text = profileLabel()
                        renderProxies(ranked)
                        importStatus.text = if (ranked.isEmpty()) "TVCrate недоступен через узлы подписки."
                            else "Доступны: ${ranked.size}. Выбран узел с наименьшей задержкой."
                    }.onFailure { error ->
                        Log.w(TAG, "Proxy refresh failed: " + error.javaClass.simpleName)
                        importStatus.text = "Не удалось обновить прокси. Повторите попытку."
                    }
                    setBusy(false)
                }
                if (!isDestroyed && restoreVpn && manualStops == stopCount &&
                    session != null && app.foregroundSession === session &&
                    VpnService.prepare(applicationContext) == null) {
                    FreeCastVpnService.connect(applicationContext)
                }
            }
        }
    }

    private fun renderProxies(proxies: List<CheckedProxy>) {
        val selected = VpnProfileStore.load(this)
        proxyRows.removeAllViews()
        deleteAction.visibility = if (selected == null) View.GONE else View.VISIBLE
        proxies.forEachIndexed { index, proxy ->
            val name = proxy.profile.name.take(85)
            val suffix = if (proxy.profile == selected) " · выбран" else ""
            proxyRows.addView(TvChrome.glassTile(this, "${index + 1}. $name · ${proxy.latencyMillis} мс$suffix",
                TvGlassDrawable.BLUE).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { if (!busy) selectProxy(proxy, proxies) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun selectProxy(proxy: CheckedProxy, proxies: List<CheckedProxy>) {
        if (runCatching { VpnProfileStore.save(this, proxy.profile) }.isFailure) {
            importStatus.text = "Не удалось сохранить выбранный узел."
            return
        }
        val restoreVpn = isVpnRunning()
        profileStatus.text = profileLabel()
        renderProxies(proxies)
        importStatus.text = "Узел выбран."
        if (restoreVpn) connect(restart = true)
    }

    private fun addAction(
        row: LinearLayout,
        title: String,
        primary: Boolean = false,
        allowWhileBusy: Boolean = false,
        action: () -> Unit,
    ): Button {
        val button = TvChrome.action(this, title, primary).apply {
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { if (!busy || allowWhileBusy) action() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            if (row.childCount > 0) marginStart = dp(8)
        })
        if (!allowWhileBusy) busyActions += button
        return button
    }

    private fun showImport(show: Boolean) {
        overview.visibility = if (show) View.GONE else View.VISIBLE
        importPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) closeImport.requestFocus()
        else if (!importAction.requestFocus()) actions.getChildAt(0).requestFocus()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        deleteAction.isEnabled = !value
        busyActions.forEach { it.isEnabled = !value }
    }

    private fun deleteProfile() {
        stopVpn()
        VpnProfileStore.clear(this)
        profileStatus.text = profileLabel()
        importStatus.text = ""
        renderProxies(emptyList())
        showImport(true)
    }

    private fun stopVpn() {
        manualStops++
        FreeCastVpnService.disconnect(this)
    }

    private fun isVpnRunning() = FreeCastVpnService.connectionState in
        setOf(FreeCastVpnService.State.CONNECTING, FreeCastVpnService.State.CONNECTED)

    private fun waitForVpnToStop() {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        fun stopping() = isVpnRunning() || FreeCastVpnService.connectionState == FreeCastVpnService.State.STOPPING
        while (stopping() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(!stopping()) { "VPN did not stop before testing" }
    }

    private fun downloadProfiles(value: String): List<VlessProfile> = try {
        VlessProfile.profilesFromSubscription(download(value, userAgent = "v2rayNG/1.10.23"))
    } catch (_: IllegalArgumentException) {
        VlessProfile.profilesFromSubscription(download(value))
    }

    private fun download(value: String, userAgent: String? = null): String {
        val connection = (URL(value).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            if (userAgent != null) setRequestProperty("User-Agent", userAgent)
            val accept = if (userAgent != null) "application/json, text/plain, */*" else "text/plain, */*"
            setRequestProperty("Accept", accept)
        }
        try {
            require(connection.responseCode == 200) { "Сервер подписки вернул ${connection.responseCode}" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= VlessProfile.MAX_SUBSCRIPTION_BYTES) { "Подписка слишком большая" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }

    private fun connect(restart: Boolean = false) {
        if (VpnProfileStore.load(this) == null) {
            importStatus.text = "Сначала добавьте профиль"
            return
        }
        val permission = VpnService.prepare(this)
        val request = if (restart) VPN_RESTART_PERMISSION_REQUEST else VPN_PERMISSION_REQUEST
        if (permission != null) startActivityForResult(permission, request) else startVpn(restart)
    }

    @Deprecated("Android VPN consent still uses startActivityForResult on API 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST || requestCode == VPN_RESTART_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) startVpn(requestCode == VPN_RESTART_PERMISSION_REQUEST)
            else importStatus.text = "Разрешение VPN не выдано"
        }
    }

    private fun startVpn(restart: Boolean) {
        if (restart) FreeCastVpnService.restart(this) else FreeCastVpnService.connect(this)
    }

    private fun profileLabel(): String = if (VpnProfileStore.load(this) == null) "Профиль не добавлен" else "VLESS-профиль добавлен"
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val STATE_IMPORT_OPEN = "importOpen"
        private const val VPN_PERMISSION_REQUEST = 814
        private const val VPN_RESTART_PERMISSION_REQUEST = 815
        private const val TAG = "VpnActivity"
    }
}
