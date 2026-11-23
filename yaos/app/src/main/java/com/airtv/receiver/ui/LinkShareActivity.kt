package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import com.airtv.receiver.R
import com.airtv.receiver.link.LinkShareServer
import com.airtv.receiver.link.PairingSecret
import com.airtv.receiver.link.RemoteCommandServer
import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom

/** Shows the LAN link inbox while the phone scans and sends a Safari page URL. */
class LinkShareActivity : Activity() {
    private var server: LinkShareServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        val pairing = TvPairingPanel(this,
            getString(R.string.pairing_link_qr),
            getString(R.string.pairing_link_title),
            getString(R.string.pairing_link_instructions))
        setContentView(TvChrome.page(this, null, getString(R.string.dashboard_send_link), null, pairing))

        val address = LanPairingAddress.find(this)
        if (address == null) {
            pairing.showFeedback(getString(R.string.pairing_network_missing))
            return
        }
        val url = "http://$address:${LinkShareServer.PORT}/"
        try {
            val next = LinkShareServer(code) { page ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        pairing.showFeedback("Ссылка получена. Открываем страницу на ТВ…")
                        startActivity(Intent(this, BroadcastWebActivity::class.java)
                            .putExtra(BroadcastWebActivity.EXTRA_URL, page))
                    }
                }
            }
            next.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = next
            pairing.showCode(url, code)
            val shortcut = TvChrome.label(this, getString(R.string.pairing_shortcut_details,
                "http://$address:${RemoteCommandServer.PORT}/command", PairingSecret.get(this)), 16f, muted = true).apply {
                visibility = View.GONE
            }
            val toggle = TvChrome.action(this, getString(R.string.pairing_shortcut_show)).apply {
                setOnClickListener {
                    val show = shortcut.visibility != View.VISIBLE
                    shortcut.visibility = if (show) View.VISIBLE else View.GONE
                    setText(if (show) R.string.pairing_shortcut_hide else R.string.pairing_shortcut_show)
                    if (show) shortcut.post {
                        shortcut.requestRectangleOnScreen(Rect(0, 0, shortcut.width, shortcut.height), false)
                    }
                }
            }
            pairing.setAction(toggle)
            pairing.addDetail(shortcut)
            toggle.requestFocus()
        } catch (error: Exception) {
            server?.stop()
            server = null
            pairing.showFeedback("Не удалось открыть приём ссылки: ${error.localizedMessage.orEmpty()}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }
}
