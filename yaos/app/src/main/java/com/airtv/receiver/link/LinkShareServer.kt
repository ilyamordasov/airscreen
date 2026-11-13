package com.airtv.receiver.link

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Short-lived LAN form. No account, cloud relay, or saved viewing history. */
class LinkShareServer(
    private val code: String,
    private val onPage: (String) -> Unit,
) : NanoHTTPD(PORT) {
    private val accepted = AtomicBoolean(false)
    private val rejected = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.GET && session.uri == "/") {
            return page(Response.Status.OK, FORM)
        }
        if (session.method != Method.POST || session.uri != "/send") {
            return page(Response.Status.NOT_FOUND, "Страница не найдена")
        }
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..MAX_FORM_BYTES ||
            !session.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded")) {
            return page(Response.Status.BAD_REQUEST, "Некорректная форма")
        }
        try {
            session.parseBody(HashMap())
        } catch (_: Exception) {
            return page(Response.Status.BAD_REQUEST, "Не удалось прочитать форму")
        }
        if (rejected.get() >= MAX_ATTEMPTS) return page(Response.Status.FORBIDDEN, "Слишком много попыток. Откройте экран передачи на ТВ заново.")
        val entered = session.parameters["code"]?.singleOrNull()
        if (entered != code) {
            rejected.incrementAndGet()
            return page(Response.Status.FORBIDDEN, "Неверный код на ТВ")
        }
        val url = session.parameters["url"]?.singleOrNull()?.let(SharedPage::parse)
            ?: return page(Response.Status.BAD_REQUEST, "Нужна ссылка на HTTPS-страницу")
        if (!accepted.compareAndSet(false, true)) return page(Response.Status.CONFLICT, "Ссылка уже отправлена")
        onPage(url)
        return page(Response.Status.OK, "Ссылка отправлена. Видео откроется на телевизоре.")
    }

    private fun page(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/html; charset=utf-8", html(message)).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun html(content: String): String = """
        <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>FreeCast TV</title><style>body{font:18px system-ui,sans-serif;max-width:34rem;margin:2rem auto;padding:0 1rem;background:#101827;color:white}input,button{box-sizing:border-box;width:100%;padding:1rem;margin:.5rem 0;border-radius:.6rem;font:inherit}button{background:#49b9c9;color:#071217;border:0}p{line-height:1.4}</style>
        <h1>FreeCast TV</h1><p>$content</p>
        <form method="post" action="/send"><label>Ссылка из Safari<input name="url" type="url" inputmode="url" maxlength="2048" placeholder="https://…" required></label>
        <label>Код на телевизоре<input name="code" type="text" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required></label>
        <button type="submit">Открыть на ТВ</button></form></html>
    """.trimIndent()

    companion object {
        const val PORT = 38751
        private const val MAX_FORM_BYTES = 8_192L
        private const val MAX_ATTEMPTS = 5
        private const val FORM = "Скопируйте адрес страницы с видео в Safari, вставьте его ниже и введите код с экрана ТВ."
    }
}
