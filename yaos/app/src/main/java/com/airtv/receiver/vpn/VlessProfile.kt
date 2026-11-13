package com.airtv.receiver.vpn

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** A deliberately narrow parser for standard VLESS share links. Never log this value. */
data class VlessProfile(
    val server: String,
    val port: Int,
    val uuid: String,
    val name: String,
    val security: String,
    val sni: String?,
    val fingerprint: String?,
    val publicKey: String?,
    val shortId: String?,
    val flow: String?,
    val transport: String,
    val path: String?,
    val host: String?,
) {
    // The default data class toString would leak credentials into crash reports.
    override fun toString(): String = "VlessProfile(name=$name, server=redacted)"

    companion object {
        fun fromSubscription(content: String): VlessProfile {
            require(content.length <= 1_000_000) { "Подписка слишком большая" }
            val plain = content.trim()
            val candidate = if (plain.contains("vless://")) plain else {
                runCatching {
                    val compact = plain.filterNot(Char::isWhitespace)
                    String(decodeBase64(compact), StandardCharsets.UTF_8)
                }.getOrElse { throw IllegalArgumentException("Подписка не содержит VLESS-ссылок") }
            }
            val link = candidate.lineSequence().map(String::trim).firstOrNull { it.startsWith("vless://") }
                ?: throw IllegalArgumentException("Подписка не содержит VLESS-ссылок")
            return parse(link)
        }

        fun parse(link: String): VlessProfile {
            require(link.length <= 8192) { "VLESS-ссылка слишком длинная" }
            val uri = runCatching { URI(link) }.getOrElse { throw IllegalArgumentException("Неверная VLESS-ссылка") }
            require(uri.scheme.equals("vless", true)) { "Нужна VLESS-ссылка" }
            val uuid = uri.userInfo ?: throw IllegalArgumentException("В ссылке нет UUID")
            runCatching { UUID.fromString(uuid) }.getOrElse { throw IllegalArgumentException("Неверный UUID") }
            val server = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("В ссылке нет сервера")
            val port = uri.port.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Неверный порт")
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { item ->
                val parts = item.split('=', limit = 2)
                decode(parts[0]) to decode(parts.getOrElse(1) { "" })
            }
            require(query["encryption"].orEmpty() in listOf("", "none")) { "Неподдерживаемое шифрование VLESS" }
            val security = query["security"].orEmpty().ifBlank { "none" }
            require(security in setOf("none", "tls", "reality")) { "Неподдерживаемая защита VLESS" }
            val transport = query["type"].orEmpty().ifBlank { "tcp" }
            require(transport in setOf("tcp", "ws")) { "Неподдерживаемый транспорт VLESS: $transport" }
            val flow = query["flow"]?.takeIf(String::isNotBlank)
            require(flow == null || flow == "xtls-rprx-vision") { "Неподдерживаемый режим VLESS" }
            if (security == "reality") require(!query["pbk"].isNullOrBlank()) { "Нет ключа Reality" }
            return VlessProfile(
                server, port, uuid, decode(uri.rawFragment.orEmpty()).ifBlank { server }, security,
                query["sni"]?.takeIf(String::isNotBlank), query["fp"]?.takeIf(String::isNotBlank),
                query["pbk"]?.takeIf(String::isNotBlank), query["sid"]?.takeIf(String::isNotBlank),
                flow, transport, query["path"]?.takeIf(String::isNotBlank),
                query["host"]?.takeIf(String::isNotBlank),
            )
        }

        private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

        // java.util.Base64 requires API 26; FreeCast supports API 24 TVs as well.
        private fun decodeBase64(value: String): ByteArray {
            val source = value.trimEnd('=')
            require(value.length - source.length <= 2 && source.length % 4 != 1)
            val output = ByteArray(source.length * 3 / 4 + 2)
            var accumulator = 0
            var bits = 0
            var count = 0
            for (character in source) {
                val digit = when (character) {
                    in 'A'..'Z' -> character - 'A'
                    in 'a'..'z' -> character - 'a' + 26
                    in '0'..'9' -> character - '0' + 52
                    '+', '-' -> 62
                    '/', '_' -> 63
                    else -> throw IllegalArgumentException("Неверная кодировка подписки")
                }
                accumulator = (accumulator shl 6) or digit
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output[count++] = (accumulator shr bits).toByte()
                    accumulator = accumulator and ((1 shl bits) - 1)
                }
            }
            return output.copyOf(count)
        }
    }
}
