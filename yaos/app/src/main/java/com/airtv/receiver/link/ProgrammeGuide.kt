package com.airtv.receiver.link

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal object ProgrammeGuide {
    data class Entry(val title: String, val startsAt: Long, val endsAt: Long?) {
        fun remainingMinutes(now: Long): Int? = endsAt?.let { end ->
            ((end - now + 59_999) / 60_000).coerceAtLeast(0).toInt()
        }
    }

    data class Window(val current: Entry?, val next: Entry?)

    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private val clockPattern = Regex("[0-9]{2}:[0-9]{2}")
    private val datePattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

    fun window(entries: List<Entry>, now: Long) = Window(
        entries.lastOrNull { it.startsAt <= now }?.takeIf { it.endsAt?.let { end -> now < end } == true },
        entries.firstOrNull { it.startsAt > now },
    )

    fun tvCrate(html: String): List<Entry> {
        val entries = Jsoup.parse(html).select("script[type=application/ld+json]").flatMap { script ->
            val body = script.data().trim()
            val schemas = runCatching {
                if (body.startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            }.getOrNull() ?: return@flatMap emptyList()
            (0 until schemas.length()).flatMap schema@{ index ->
                val items = schemas.optJSONObject(index)?.takeIf { it.optString("@type") == "ItemList" }
                    ?.optJSONArray("itemListElement") ?: return@schema emptyList()
                (0 until items.length()).mapNotNull { position ->
                    val item = items.optJSONObject(position)?.optJSONObject("item") ?: return@mapNotNull null
                    if (item.optString("@type") != "BroadcastEvent") return@mapNotNull null
                    val title = item.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val start = parse(item.optString("startDate"), "yyyy-MM-dd'T'HH:mm:ssXXX") ?: return@mapNotNull null
                    val end = parse(item.optString("endDate"), "yyyy-MM-dd'T'HH:mm:ssXXX")?.takeIf { it > start }
                    Entry(title, start, end)
                }
            }
        }
        return normalize(entries)
    }

    fun iptvX(html: String): List<Entry> {
        val entries = Jsoup.parse(html).select(".epg-day[data-d]").flatMap { day ->
            day.select(".epg-list > li").mapNotNull { item ->
                val start = moscowTime(day.attr("data-d"), item.selectFirst(".t")?.text().orEmpty())
                    ?: return@mapNotNull null
                val title = item.selectFirst("summary")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(title, start, null)
            }
        }
        return normalize(entries)
    }

    fun merge(days: Iterable<List<Entry>>) = normalize(days.flatten())

    fun moscowTime(date: String, time: String): Long? =
        if (datePattern.matches(date) && clockPattern.matches(time)) parse("$date $time", "yyyy-MM-dd HH:mm") else null

    fun moscowClock(millis: Long): String = formatter("HH:mm").format(Date(millis))

    fun moscowDate(now: Long, dayOffset: Int): String = Calendar.getInstance(moscow).run {
        timeInMillis = now
        add(Calendar.DAY_OF_MONTH, dayOffset)
        formatter("yyyy-MM-dd").format(time)
    }

    private fun normalize(entries: List<Entry>): List<Entry> {
        val sorted = entries.sortedBy(Entry::startsAt).distinctBy(Entry::startsAt)
        return sorted.mapIndexed { index, entry ->
            val next = sorted.getOrNull(index + 1)?.startsAt
            val end = if (next == null) entry.endsAt else minOf(next, entry.endsAt ?: next)
            entry.copy(endsAt = end)
        }
    }

    private fun formatter(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply {
        timeZone = moscow
        isLenient = false
    }

    private fun parse(value: String, pattern: String): Long? {
        val position = ParsePosition(0)
        val parsed = formatter(pattern).parse(value, position) ?: return null
        return parsed.time.takeIf { position.index == value.length }
    }
}

/** The guide uses public listings independently of the TVCrate playback resolver. */
internal class ProgrammeGuideClient {
    enum class Channel(val page: String, val providerId: String) {
        MATCH_PREMIER(TvCrateResolver.MATCH_PREMIER_URL, "match-premer-501"),
        OKKO_FOOTBALL("https://tvcrate.com/channel/okko-futbol-hd-535", "okko-football"),
        OKKO_PRIME("https://tvcrate.com/channel/okko-prajm-sport-hd-534", "okko-sport-2"),
    }

    fun load(channel: Channel, now: Long): List<ProgrammeGuide.Entry> = when (channel) {
        Channel.MATCH_PREMIER -> {
            val tomorrow = ProgrammeGuide.moscowDate(now, 1)
            val base = "https://tvcrate.com/tv-programma/${channel.providerId}"
            val current = ProgrammeGuide.tvCrate(read(base))
            val next = runCatching { ProgrammeGuide.tvCrate(read("$base/$tomorrow")) }.getOrDefault(emptyList())
            ProgrammeGuide.merge(listOf(current, next))
        }
        else -> ProgrammeGuide.iptvX(read("https://epg.iptvx.one/id/${channel.providerId}"))
    }

    private fun read(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", TvCrateResolver.USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Empty programme guide response")
            if (!response.isSuccessful || body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Programme guide unavailable")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    TvCrateResolver.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Programme guide exceeded size limit")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
    }
}
