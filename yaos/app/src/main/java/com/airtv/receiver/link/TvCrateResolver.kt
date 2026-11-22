package com.airtv.receiver.link

import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** A short-lived, TV-owned native HLS session. Never persist or log [mediaUrl]. */
data class TvCrateStream(
    val mediaUrl: String,
    val sourcePage: String,
    val requestHeaders: Map<String, String>,
    val expiresAtEpochSeconds: Long?,
)

/** Injectable HTTP boundary: tests exercise the real resolution flow without live tokens. */
fun interface TvCrateTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): TvCrateResponse
}

data class TvCrateResponse(val statusCode: Int, val body: String)

internal class TvCrateHttpException(val statusCode: Int, message: String) : IOException(message)

/**
 * Reads the public iframe configuration, then uses the same native-HLS endpoint as
 * TVCrate's player. n=1 is essential: its playlists include signed segment URLs.
 * Blocking; call from a cancellable background worker, never the Android UI thread.
 */
class TvCrateResolver(
    private val transport: TvCrateTransport = TvCrateHttpTransport(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    @Throws(IOException::class)
    fun resolve(pageUrl: String, fresh: Boolean = false): TvCrateStream {
        val source = supportedPage(pageUrl)
            ?: throw IOException("Некорректная ссылка TVCrate. Нужна страница канала или плеера.")
        ensureActive()
        val playerUrl = if (isPlayer(URI(source))) source else {
            val html = read(source, pageHeaders(ORIGIN + "/"))
            playerFromHtml(html, source)
                ?: throw IOException("Плеер TVCrate не найден. Попробуйте открыть страницу.")
        }
        val html = read(playerUrl, pageHeaders(source))
        val config = playerConfig(html)
        val key = playerKey(URI(playerUrl))
            ?: throw IOException("В плеере TVCrate отсутствует ключ канала.")
        if (config.key != null && config.key != key) {
            throw IOException("Страница TVCrate вернула другой канал. Обновите ссылку.")
        }
        val headers = pageHeaders(playerUrl)
        val candidate = if (config.pinnedUrl.isNotEmpty() && !config.resolveMode) {
            config.pinnedUrl
        } else {
            // This is the public request made by TVCrate itself. Do not cache its result.
            val apiUrl = "$TOKEN_ENDPOINT?key=$key&n=1" +
                if (fresh && config.resolveMode) "&fresh=1" else ""
            val apiHeaders = headers + mapOf("Accept" to "application/json", "Cache-Control" to "no-cache")
            val body = read(apiUrl, apiHeaders)
            val json = try { JSONObject(body) } catch (_: Exception) {
                throw IOException("TVCrate вернул некорректный ответ вместо адреса эфира.")
            }
            (json.opt("url") as? String)?.takeIf { it.isNotBlank() }
                ?: throw IOException("TVCrate не предоставил адрес эфира. Попробуйте позднее.")
        }
        val mediaUrl = hlsUrl(candidate)
            ?: throw IOException("TVCrate не вернул поддерживаемый HTTPS HLS-поток.")
        val expires = queryParameter(URI(mediaUrl), "expires")?.let { value ->
            value.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IOException("Некорректный срок действия ссылки TVCrate.")
        }
        if (expires != null && expires <= nowSeconds()) {
            throw IOException("Срок действия ссылки TVCrate истёк. Нажмите «Повторить».")
        }
        ensureActive()
        return TvCrateStream(mediaUrl, source, headers, expires)
    }

    private fun read(url: String, headers: Map<String, String>): String {
        ensureActive()
        val response = transport.get(url, headers)
        ensureActive()
        when (response.statusCode) {
            in 200..299 -> Unit
            403 -> throw TvCrateHttpException(403, "TVCrate отклонил запрос (HTTP 403). Можно открыть страницу.")
            404 -> throw TvCrateHttpException(404, "Канал или плеер TVCrate не найден (HTTP 404).")
            429 -> throw TvCrateHttpException(429, "TVCrate просит подождать (HTTP 429). Повторите позже.")
            else -> throw TvCrateHttpException(response.statusCode, "TVCrate временно недоступен (HTTP ${response.statusCode}).")
        }
        if (response.body.length > MAX_RESPONSE_BYTES) throw IOException("Ответ TVCrate слишком большой.")
        return response.body
    }

    companion object {
        const val MATCH_PREMIER_URL = "https://tvcrate.com/channel/match-premer-501"
        const val ORIGIN = "https://tvcrate.com"
        const val USER_AGENT = "FreeCast-TV/1.0"
        internal const val TOKEN_ENDPOINT = "https://live.tvcrate.com/gettoken.php"
        internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private val CHANNEL_PATH = Regex("/channel/[a-zA-Z0-9][a-zA-Z0-9_-]*/?")
        private val KEY = Regex("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        private val IFRAME = Regex("<iframe\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SCRIPT = Regex("<script\\b[^>]*>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val ATTRIBUTE = Regex("""([a-zA-Z][a-zA-Z0-9:_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>`]+))""")
        private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|quot|apos|lt|gt);", RegexOption.IGNORE_CASE)

        fun supports(input: String): Boolean = supportedPage(input) != null

        private fun supportedPage(input: String): String? {
            val page = SharedPage.parse(input)?.substringBefore('#') ?: return null
            val uri = runCatching { URI(page) }.getOrNull() ?: return null
            if (!isPageHost(uri.host) || (uri.port != -1 && uri.port != 443)) return null
            if (uri.rawPath != uri.path) return null
            return page.takeIf { CHANNEL_PATH.matches(uri.path.orEmpty()) || (isPlayer(uri) && playerKey(uri) != null) }
        }

        private fun isPageHost(host: String?): Boolean =
            host.equals("tvcrate.com", true) || host.equals("www.tvcrate.com", true)

        private fun isPlayer(uri: URI): Boolean = uri.path in setOf("/player", "/player/", "/player/index.php")

        private fun playerKey(uri: URI): String? = queryParameter(uri, "key")?.takeIf { KEY.matches(it) }

        private fun queryParameter(uri: URI, name: String): String? {
            val matches = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val key = decodeQuery(part.substringBefore('=')) ?: return@mapNotNull null
                if (key != name) return@mapNotNull null
                decodeQuery(part.substringAfter('=', ""))
            }
            return matches.singleOrNull()
        }

        private fun decodeQuery(value: String): String? =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

        internal fun playerFromHtml(html: String, pageUrl: String): String? {
            // Ignore templates inside scripts/comments and unrelated advertising iframes.
            val markup = SCRIPT.replace(COMMENT.replace(html, ""), "")
            val candidates = IFRAME.findAll(markup).mapNotNull { tag ->
                val src = ATTRIBUTE.findAll(tag.value).firstOrNull { it.groupValues[1].equals("src", true) }
                    ?.groupValues?.drop(2)?.firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
                val resolved = runCatching { URI(pageUrl).resolve(decodeHtml(src)).toString() }.getOrNull()
                    ?: return@mapNotNull null
                supportedPage(resolved)?.takeIf { isPlayer(URI(it)) }
            }.distinct().toList()
            // Never silently select a different channel on multi-player/event pages.
            return candidates.singleOrNull()
        }

        private data class PlayerConfig(val key: String?, val pinnedUrl: String, val resolveMode: Boolean)

        private fun playerConfig(html: String): PlayerConfig {
            val scripts = SCRIPT.findAll(COMMENT.replace(html, "")).map { it.groupValues[1] }.toList()
            val script = scripts.singleOrNull { Regex("\\bconst\\s+STREAM_KEY\\s*=").containsMatchIn(it) }
                ?: throw IOException("Не удалось прочитать настройки плеера TVCrate. Можно открыть страницу.")
            fun stringConstant(name: String): String? {
                val regex = Regex("""\bconst\s+$name\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\s*;""")
                val match = regex.findAll(script).toList().singleOrNull() ?: return null
                return try { JSONTokener(match.groupValues[1]).nextValue() as? String } catch (_: Exception) { null }
            }
            val key = stringConstant("STREAM_KEY")?.takeIf { KEY.matches(it) }
                ?: throw IOException("Не удалось прочитать ключ плеера TVCrate.")
            val pinned = stringConstant("PIN_SRC") ?: ""
            val resolve = Regex("\\bconst\\s+RESOLVE_MODE\\s*=\\s*true\\s*;").containsMatchIn(script)
            return PlayerConfig(key, pinned, resolve)
        }

        private fun decodeHtml(value: String): String = ENTITY.replace(value) { match ->
            when (val entity = match.groupValues[1].lowercase(Locale.ROOT)) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val point = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    if (point != null && Character.isValidCodePoint(point)) String(Character.toChars(point)) else match.value
                }
            }
        }

        private fun hlsUrl(value: String): String? {
            // Keep the signed query byte-for-byte. Rebuilding it can invalidate the signature.
            val url = SharedPage.parse(value) ?: return null
            val uri = URI(url)
            return url.takeIf { uri.fragment == null && uri.path.orEmpty().endsWith(".m3u8", true) }
        }

        private fun pageHeaders(referer: String) = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to ORIGIN,
            "Referer" to referer,
        )

        internal fun ensureActive() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Загрузка TVCrate отменена.")
        }
    }
}

/** Small bounded HTTPS client; no cookies, scripts, global CookieHandler or unsafe redirects. */
class TvCrateHttpTransport : TvCrateTransport {
    override fun get(url: String, headers: Map<String, String>): TvCrateResponse {
        var current = URI(url)
        repeat(4) { attempt ->
            TvCrateResolver.ensureActive()
            val host = current.host?.lowercase(Locale.ROOT)
            if (!current.scheme.equals("https", true) || current.rawUserInfo != null ||
                (current.port != -1 && current.port != 443) ||
                host !in setOf("tvcrate.com", "www.tvcrate.com", "live.tvcrate.com")) {
                throw IOException("Неподдерживаемый адрес или перенаправление TVCrate.")
            }
            val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location")
                        ?: throw IOException("TVCrate вернул пустое перенаправление.")
                    if (attempt == 3) throw IOException("Слишком много перенаправлений TVCrate.")
                    current = runCatching { current.resolve(next) }.getOrElse {
                        throw IOException("Некорректное перенаправление TVCrate.")
                    }
                    return@repeat
                }
                if (code !in 200..299) return TvCrateResponse(code, "")
                if (connection.contentLengthLong > TvCrateResolver.MAX_RESPONSE_BYTES) {
                    throw IOException("Ответ TVCrate слишком большой.")
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8_192)
                    while (true) {
                        TvCrateResolver.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > TvCrateResolver.MAX_RESPONSE_BYTES) {
                            throw IOException("Ответ TVCrate слишком большой.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                return TvCrateResponse(code, output.toString("UTF-8"))
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Не удалось загрузить TVCrate.")
    }
}
