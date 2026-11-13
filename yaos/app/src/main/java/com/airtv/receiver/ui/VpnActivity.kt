package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airtv.receiver.vpn.FreeCastVpnService
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
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/** Import from a phone, then connect only FreeCast to the VLESS tunnel. */
class VpnActivity : Activity() {
    private var server: VpnImportServer? = null
    private lateinit var status: TextView
    private lateinit var profileStatus: TextView
    private lateinit var importStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
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
        profileStatus = label(profileLabel(), 19f)
        status = label(FreeCastVpnService.state, 21f)
        importStatus = label("", 18f)
        val info = TvChrome.panel(this)
        info.addView(TvChrome.label(this, "СОСТОЯНИЕ", 13f).apply { setTextColor(getColor(com.airtv.receiver.R.color.accent)) })
        info.addView(status)
        info.addView(profileStatus)
        info.addView(importStatus)
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        val qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        row.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)))
        val instructions = label("", 18f)
        val instructionPanel = TvChrome.panel(this)
        instructionPanel.addView(instructions)
        row.addView(instructionPanel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(20) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(buttons)
        buttons.addView(TvChrome.action(this, "Подключить", true).apply {
            setOnClickListener { connect() }
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        buttons.addView(TvChrome.action(this, "Отключить").apply {
            setOnClickListener { stopService(Intent(this@VpnActivity, FreeCastVpnService::class.java)) }
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(12) })
        buttons.addView(TvChrome.action(this, "Удалить профиль").apply {
            setOnClickListener {
                stopService(Intent(this@VpnActivity, FreeCastVpnService::class.java))
                VpnProfileStore.clear(this@VpnActivity)
                profileStatus.text = profileLabel()
            }
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply { leftMargin = dp(12) })
        setContentView(TvChrome.page(this, TvChrome.Section.VPN, "FREECAST • СЕТЬ", "VPN",
            "Подключение только для FreeCast TV", root))

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
            instructions.text = "На iPhone откройте QR-код, вставьте ссылку подписки или vless:// ссылку и введите код $token.\n\nПодписка хранится только на ТВ. После импорта нажмите «Подключить» и разрешите VPN в Android."
        } catch (_: Exception) {
            server?.stop()
            server = null
            instructions.text = "Не удалось открыть страницу импорта на ТВ."
        }
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { server?.stop(); server = null; super.onDestroy() }

    private fun import(value: String) {
        runOnUiThread { importStatus.text = "Загружаем профиль…" }
        Thread {
            try {
                val content = if (value.startsWith("vless://")) value else download(value)
                val profile = VlessProfile.fromSubscription(content)
                stopService(Intent(this, FreeCastVpnService::class.java))
                VpnProfileStore.save(this, profile)
                runOnUiThread {
                    if (!isDestroyed) {
                        profileStatus.text = profileLabel()
                        importStatus.text = "Профиль сохранён. Нажмите «Подключить»."
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed) importStatus.text = when (error.message) {
                        "Подписка не содержит VLESS-ссылок" -> "Подписка не содержит обычных VLESS-ссылок. Нужен другой формат импорта."
                        "Подписка слишком большая" -> "Подписка слишком большая."
                        else -> "Не удалось импортировать профиль. Проверьте ссылку и формат."
                    }
                }
            }
        }.start()
    }

    private fun download(value: String): String {
        val connection = (URL(value).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "text/plain, */*")
        }
        try {
            require(connection.responseCode == 200) { "Сервер подписки вернул ${connection.responseCode}" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1_000_000) { "Подписка слишком большая" }
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
        ContextCompat.startForegroundService(this, Intent(this, FreeCastVpnService::class.java))
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

    companion object { private const val VPN_PERMISSION_REQUEST = 814 }
}
