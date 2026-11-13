package com.airtv.receiver.vpn

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived, one-use import page. The pairing secret is not the VPN credential. */
class VpnImportServer(private val token: String, private val onImport: (String) -> Unit) : NanoHTTPD(PORT) {
    private val imported = AtomicBoolean(false)
    private val failures = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/") return page(Response.Status.OK, FORM)
        if (session.method != Method.POST || session.uri != "/import") return page(Response.Status.NOT_FOUND, "Страница не найдена")
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..MAX_BYTES ||
            !session.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) {
            return page(Response.Status.BAD_REQUEST, "Неверная форма")
        }
        if (failures.get() >= 5) return page(Response.Status.FORBIDDEN, "Откройте экран VPN на ТВ заново")
        try { session.parseBody(HashMap()) } catch (_: Exception) {
            return page(Response.Status.BAD_REQUEST, "Не удалось прочитать форму")
        }
        val supplied = session.parameters["token"]?.singleOrNull().orEmpty()
        if (!MessageDigest.isEqual(token.toByteArray(), supplied.toByteArray())) {
            failures.incrementAndGet()
            return page(Response.Status.FORBIDDEN, "Неверный код")
        }
        val value = session.parameters["profile"]?.singleOrNull()?.trim().orEmpty()
        if (value.length !in 16..8192 || !(value.startsWith("vless://") || value.startsWith("https://"))) {
            return page(Response.Status.BAD_REQUEST, "Нужна ссылка vless:// или https://")
        }
        if (!imported.compareAndSet(false, true)) return page(Response.Status.CONFLICT, "Профиль уже передан")
        onImport(value)
        return page(Response.Status.OK, "Профиль передан. Проверьте экран телевизора.")
    }

    private fun page(status: Response.Status, content: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", html(content)).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Referrer-Policy", "no-referrer")
        }

    private fun html(content: String) = """
        <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>FreeCast VPN</title><style>body{font:18px system-ui;max-width:34rem;margin:2rem auto;padding:1rem;background:#101827;color:white}input,button{box-sizing:border-box;width:100%;padding:1rem;margin:.5rem 0;border-radius:.6rem;font:inherit}button{background:#49b9c9;color:#071217;border:0}</style>
        <h1>FreeCast VPN</h1><p>$content</p><form method="post" action="/import">
        <label>Ссылка подписки или VLESS<input name="profile" type="text" maxlength="8192" required autocomplete="off"></label>
        <label>Код на ТВ<input name="token" type="text" required autocomplete="off"></label>
        <button type="submit">Передать на ТВ</button></form></html>
    """.trimIndent()

    companion object {
        const val PORT = 38754
        private const val MAX_BYTES = 12_000L
        private const val FORM = "Вставьте ссылку подписки из VPN-приложения или отдельную vless:// ссылку."
    }
}
