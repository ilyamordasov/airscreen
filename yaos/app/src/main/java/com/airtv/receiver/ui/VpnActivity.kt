package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
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
    private lateinit var importPanel: LinearLayout
    private lateinit var actions: LinearLayout
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        profileStatus = TvChrome.label(this, profileLabel(), 17f, muted = true)
        status = TvChrome.label(this, FreeCastVpnService.state, 21f)
        importStatus = TvChrome.label(this, "", 16f).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            doAfterTextChanged { value -> visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE }
        }
        val info = TvChrome.panel(this)
        info.addView(TvChrome.label(this, "СОСТОЯНИЕ", 13f).apply { setTextColor(getColor(R.color.accent)) })
        info.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(profileStatus, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        })
        info.addView(importStatus)
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        importPanel = row
        val qr = ImageView(this).apply {
            id = R.id.vpn_import_qr
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.dashboard_vpn_qr)
        }
        row.addView(qr, LinearLayout.LayoutParams(dp(156), dp(156)))
        val instructions = label("", 18f)
        val instructionPanel = TvChrome.panel(this)
        instructionPanel.addView(instructions)
        row.addView(instructionPanel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20) })
        instructionPanel.addView(TvChrome.action(this, "Удалить профиль").apply {
            setOnClickListener { if (!busy) deleteProfile() }
        })
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addAction("Обновить прокси", primary = true, action = ::updateProxies)
        addAction("Подключить", action = ::connect)
        addAction("Отключить", action = ::stopVpn)
        addAction("Импорт по QR") { showImport(importPanel.visibility != View.VISIBLE) }
        proxyRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(LinearLayout(this@VpnActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(proxyRows)
                addView(row)
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        renderProxies(VpnProfileStore.proxies(this))
        setContentView(TvChrome.page(this, TvChrome.Section.VPN, "VPN",
            "Подключение только для AirScreen", root))
        actions.getChildAt(0).requestFocus()

        val address = localAddress()
        if (address == null) {
            instructions.text = "Не найден адрес локальной сети телевизора."
            return
        }
        val token = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        val url = "http://$address:${VpnImportServer.PORT}/"
        try {
            server = VpnImportServer(token) { value -> import(value) }.also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            qr.setImageBitmap(qrBitmap(url))
            instructions.text = "Откройте QR-код на телефоне, вставьте ссылку подписки или vless:// и введите код $token."
        } catch (_: Exception) {
            server?.stop()
            server = null
            instructions.text = "Не удалось открыть страницу импорта на ТВ."
        }
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
            importStatus.text = "Добавьте ссылку подписки через QR-код."
            showImport(true)
            return
        }
        val restoreVpn = isVpnRunning()
        val app = application as FreeCastApplication
        val session = app.foregroundSession
        if (restoreVpn) stopVpn()
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
                if (restoreVpn && session != null && app.foregroundSession === session &&
                    VpnService.prepare(applicationContext) == null) {
                    FreeCastVpnService.connect(applicationContext)
                }
            }
        }
    }

    private fun renderProxies(proxies: List<CheckedProxy>) {
        val selected = VpnProfileStore.load(this)
        proxyRows.removeAllViews()
        proxies.forEachIndexed { index, proxy ->
            val name = proxy.profile.name.take(85)
            val suffix = if (proxy.profile == selected) " · выбран" else ""
            proxyRows.addView(TvChrome.action(this, "${index + 1}. $name · ${proxy.latencyMillis} мс$suffix").apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setOnClickListener { if (!busy) selectProxy(proxy, proxies) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        showImport(proxies.isEmpty())
    }

    private fun selectProxy(proxy: CheckedProxy, proxies: List<CheckedProxy>) {
        if (runCatching { VpnProfileStore.save(this, proxy.profile) }.isFailure) {
            importStatus.text = "Не удалось сохранить выбранный узел."
            return
        }
        val restoreVpn = isVpnRunning()
        val app = application as FreeCastApplication
        val session = app.foregroundSession
        if (restoreVpn) stopVpn()
        profileStatus.text = profileLabel()
        renderProxies(proxies)
        importStatus.text = "Узел выбран."
        if (restoreVpn) {
            setBusy(true)
            worker.execute {
                val stopped = runCatching { waitForVpnToStop() }.isSuccess
                handler.post {
                    if (!isDestroyed) {
                        setBusy(false)
                        if (stopped && session != null && app.foregroundSession === session) connect()
                        if (!stopped) importStatus.text = "Повторите подключение VPN."
                    }
                }
            }
        }
    }

    private fun addAction(title: String, primary: Boolean = false, action: () -> Unit) {
        actions.addView(TvChrome.action(this, title, primary).apply {
            setOnClickListener { if (!busy) action() }
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { if (actions.childCount > 0) leftMargin = dp(10) })
    }

    private fun showImport(show: Boolean) {
        importPanel.visibility = if (show) View.VISIBLE else View.GONE
        proxyRows.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        for (i in 0 until actions.childCount) actions.getChildAt(i).isEnabled = !value
    }

    private fun deleteProfile() {
        stopVpn()
        VpnProfileStore.clear(this)
        profileStatus.text = profileLabel()
        importStatus.text = ""
        renderProxies(emptyList())
    }

    private fun stopVpn() {
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

    private fun connect() {
        if (VpnProfileStore.load(this) == null) {
            importStatus.text = "Сначала добавьте профиль"
            return
        }
        val permission = VpnService.prepare(this)
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST) else startVpn()
    }

    @Deprecated("Android VPN consent still uses startActivityForResult on API 24")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) startVpn() else importStatus.text = "Разрешение VPN не выдано"
        }
    }

    private fun startVpn() {
        FreeCastVpnService.connect(this)
    }

    private fun profileLabel(): String = if (VpnProfileStore.load(this) == null) "Профиль не добавлен" else "VLESS-профиль добавлен"
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun qrBitmap(value: String): Bitmap {
        val pixels = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 280, 280)
        return Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 280) for (x in 0 until 280) setPixel(x, y, if (pixels[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        private const val VPN_PERMISSION_REQUEST = 814
        private const val TAG = "VpnActivity"
    }
}
