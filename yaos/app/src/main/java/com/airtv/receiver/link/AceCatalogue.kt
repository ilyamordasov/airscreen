package com.airtv.receiver.link

import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.util.Locale

internal data class AceSource private constructor(val kind: Kind, val id: String) {
    enum class Kind(val parameter: String) { CONTENT_ID("content_id"), INFOHASH("infohash") }

    val parameter: String get() = "${kind.parameter}=$id"

    companion object {
        private val idPattern = Regex("[0-9a-fA-F]{40}")

        fun parse(kind: Kind, value: String?): AceSource? =
            value?.takeIf(idPattern::matches)?.let { AceSource(kind, it.lowercase(Locale.ROOT)) }
    }
}

internal object AceCatalogue {
    enum class Report { WORKING, UNKNOWN, BROKEN }

    data class DirectoryEntry(val source: AceSource, val report: Report, val reportAt: String)
    data class SearchEntry(val source: AceSource, val title: String)

    fun directory(html: String): List<DirectoryEntry> = Jsoup.parse(html).select("#acestreamids li.acestreamidID")
        .mapNotNull { row ->
            val link = row.selectFirst(".col_id a[href^=acestream://]")?.attr("href") ?: return@mapNotNull null
            val source = AceSource.parse(AceSource.Kind.CONTENT_ID, link.removePrefix("acestream://"))
                ?: return@mapNotNull null
            fun reportedAt(selector: String) = row.selectFirst(selector)?.closest(".vote")
                ?.selectFirst("time[datetime]")?.attr("datetime").orEmpty()
            val working = reportedAt("i.goodId")
            val broken = reportedAt("i.broken")
            DirectoryEntry(source, when {
                working.isEmpty() && broken.isEmpty() -> Report.UNKNOWN
                working > broken -> Report.WORKING
                else -> Report.BROKEN
            }, maxOf(working, broken))
        }
        .sortedWith(compareBy<DirectoryEntry> { it.report }.thenByDescending { it.reportAt })
        .distinctBy(DirectoryEntry::source)
        .take(8)

    fun search(response: JSONObject): List<SearchEntry> {
        val channels = response.getJSONObject("result").getJSONArray("results")
        return buildList {
            for (index in 0 until channels.length()) {
                val channel = channels.optJSONObject(index) ?: continue
                val title = channel.optString("name").trim().take(90)
                val items = channel.optJSONArray("items") ?: continue
                for (item in 0 until items.length()) {
                    val source = AceSource.parse(AceSource.Kind.INFOHASH, items.optJSONObject(item)?.optString("infohash"))
                        ?: continue
                    add(SearchEntry(source, title))
                }
            }
        }.distinctBy(SearchEntry::source).take(12)
    }

    fun read(connection: HttpURLConnection): String = try {
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            buildString {
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (length + count > 1_000_000) throw IOException("Ace Stream response exceeds limit")
                    append(buffer, 0, count)
                }
            }
        }
    } finally {
        connection.disconnect()
    }
}
