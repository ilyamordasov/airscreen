package com.airtv.receiver.link

import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest

/** Authenticated LAN endpoint for the iOS Share Sheet Shortcut. */
class RemoteCommandServer(
    private val token: String,
    private val onPage: (String) -> Unit,
    port: Int = PORT,
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/command") {
            return reply(Response.Status.NOT_FOUND, "Команда не найдена")
        }
        val length = session.headers["content-length"]?.toLongOrNull()
        val contentType = session.headers["content-type"].orEmpty().lowercase()
        if (length == null || length !in 1..MAX_BYTES ||
            !(contentType.startsWith("application/x-www-form-urlencoded") ||
                contentType.startsWith("multipart/form-data; boundary="))) {
            return reply(Response.Status.BAD_REQUEST, "Неверный формат команды")
        }
        try {
            session.parseBody(HashMap())
        } catch (_: Exception) {
            return reply(Response.Status.BAD_REQUEST, "Не удалось прочитать команду")
        }
        val supplied = session.parameters["token"]?.singleOrNull().orEmpty()
        if (!MessageDigest.isEqual(supplied.toByteArray(), token.toByteArray())) {
            return reply(Response.Status.FORBIDDEN, "Неверный код сопряжения")
        }
        val url = session.parameters["url"]?.singleOrNull()?.let(SharedPage::parse)
            ?: return reply(Response.Status.BAD_REQUEST, "Нужна ссылка HTTPS")
        onPage(url)
        return reply(Response.Status.OK, "Открываем на ТВ")
    }

    private fun reply(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    companion object {
        const val PORT = 38752
        private const val MAX_BYTES = 8_192L
    }
}
