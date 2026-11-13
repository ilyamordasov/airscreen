package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.airtv.receiver.link.LinkShareServer
import com.airtv.receiver.link.PairingSecret
import com.airtv.receiver.link.RemoteCommandServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import kotlin.math.roundToInt

/** Shows the LAN link inbox while the phone scans and sends a Safari page URL. */
class LinkShareActivity : Activity() {
    private var server: LinkShareServer? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        status = label("Подготавливаем приём ссылки…", 21f)
        val statusPanel = TvChrome.panel(this)
        statusPanel.addView(status)
        root.addView(statusPanel, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })
        val qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        row.addView(qr, LinearLayout.LayoutParams(dp(220), dp(220)))
        val instructions = label("", 18f)
        val instructionsPanel = TvChrome.panel(this)
        instructionsPanel.addView(instructions)
        row.addView(instructionsPanel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(24) })
        setContentView(TvChrome.page(this, TvChrome.Section.BROADCASTS, "FREECAST • IPHONE",
            "Передать ссылку", "Откройте страницу с видео на телевизоре", root))

        val address = localAddress()
        if (address == null) {
            status.text = "У телевизора нет адреса локальной сети."
            return
        }
        val url = "http://$address:${LinkShareServer.PORT}/"
        try {
            val next = LinkShareServer(code) { page ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        status.text = "Ссылка получена. Открываем страницу на ТВ…"
                        startActivity(Intent(this, BroadcastWebActivity::class.java)
                            .putExtra(BroadcastWebActivity.EXTRA_URL, page))
                    }
                }
            }
            next.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = next
            qr.setImageBitmap(qrBitmap(url))
            status.text = "Откройте камеру iPhone и наведите её на QR-код."
            instructions.text = "Быстрая передача из Safari: один раз настройте команду «На FreeCast TV» в iOS Shortcuts.\n" +
                "Адрес: http://$address:${RemoteCommandServer.PORT}/command\n" +
                "Ключ: ${PairingSecret.get(this)}\n\n" +
                "Без команды: скопируйте ссылку в Safari, откройте QR-код и введите код $code."
        } catch (error: Exception) {
            server?.stop()
            server = null
            status.text = "Не удалось открыть приём ссылки: ${error.localizedMessage.orEmpty()}"
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun label(value: String, size: Float): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }

    private fun qrBitmap(value: String): Bitmap {
        val pixels = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 280, 280)
        return Bitmap.createBitmap(280, 280, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 280) for (x in 0 until 280) {
                setPixel(x, y, if (pixels[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
