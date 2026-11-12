package com.airtv.receiver.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.airtv.receiver.R
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** TV-side directory of official Spartak football broadcasts and channel pages. */
class BroadcastsActivity : Activity() {
    private lateinit var tiles: LinearLayout
    private lateinit var status: TextView
    private lateinit var aceStatus: TextView
    private lateinit var directoryStatus: TextView
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcasts)
        tiles = findViewById(R.id.broadcasts_tiles)
        status = findViewById(R.id.broadcasts_status)
        aceStatus = findViewById(R.id.ace_status)
        directoryStatus = findViewById(R.id.directory_status)
        findViewById<Button>(R.id.broadcasts_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        val generation = ++loadGeneration
        tiles.removeAllViews()
        addTile("Матч ТВ — бесплатный прямой эфир", "https://matchtv.ru/video/channel/matchtv")
        addTile("Матч Премьер — по подписке (приложение МАТЧ!)", "https://matchtv.ru/match-app")
        addTile("Все трансляции Спартака", CATALOG_URL)
        status.text = "Обновляем список матчей…"
        aceStatus.text = "Ищем P2P-эфиры через Ace Stream Engine…"
        directoryStatus.text = "Обновляем P2P-каталог…"
        Thread {
            val result = runCatching {
                val connection = (URL(CATALOG_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 FreeCast-TV")
                }
                try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { html ->
                    val links = extractMatchLinks(html)
                    links.forEach { addTile(it.first, it.second) }
                    status.text = if (links.isEmpty()) "Список матчей недоступен. Откройте сайт трансляций." else
                        "Матчи и записи — источник: Матч ТВ"
                }.onFailure {
                    status.text = "Не удалось обновить список. Проверьте сеть или откройте сайт трансляций."
                }
            }
        }.start()
        loadAceStreams(generation)
        loadDirectory(generation)
    }

    private fun loadDirectory(generation: Int) {
        Thread {
            val result = runCatching {
                val connection = (URL(P2P_DIRECTORY_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 FreeCast-TV")
                }
                val html = try {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                val text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
                val start = text.indexOf("ContentID")
                if (start < 0) emptyList() else {
                    val end = text.indexOf("View all", start).takeIf { it > start } ?: text.length
                    Regex("[a-fA-F0-9]{40}").findAll(text.substring(start, end))
                        .map { it.value.lowercase() }.distinct().take(8).toList()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { ids ->
                    ids.forEachIndexed { index, id ->
                        addTile("P2P · Матч Премьер · вариант ${index + 1} (не проверен)",
                            "acestream:?content_id=$id")
                    }
                    directoryStatus.text = if (ids.isEmpty()) "P2P-каталог не содержит эфиров." else
                        "P2P-каталог: ${ids.size} вариантов, работоспособность не проверена"
                }.onFailure {
                    directoryStatus.text = "P2P-каталог сейчас недоступен."
                }
            }
        }.start()
    }

    private fun loadAceStreams(generation: Int) {
        Thread {
            val result = runCatching {
                listOf("Спартак", "Матч Премьер").flatMap { query ->
                    val url = "http://localhost:6878/search?query=${Uri.encode(query)}&page_size=20"
                    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 2_000
                        readTimeout = 3_000
                    }
                    try {
                        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        val channels = json.getJSONObject("result").getJSONArray("results")
                        buildList {
                            for (i in 0 until channels.length()) {
                                val channel = channels.getJSONObject(i)
                                val name = channel.optString("name").take(90)
                                val items = channel.optJSONArray("items") ?: continue
                                for (j in 0 until items.length()) {
                                    val item = items.getJSONObject(j)
                                    val hash = item.optString("infohash")
                                    if (hash.matches(Regex("[a-fA-F0-9]{40}"))) {
                                        add(Triple(name, hash, item.optInt("status")))
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }.distinctBy { it.second }.take(12)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                result.onSuccess { streams ->
                    streams.forEach { (name, hash, availability) ->
                        addAceTile("Ace Stream · $name${if (availability == 2) " · доступен" else ""}", hash)
                    }
                    aceStatus.text = if (streams.isEmpty()) "Ace Stream: эфиры «Спартака» не найдены." else
                        "Ace Stream: ${streams.size} P2P-эфиров"
                }.onFailure {
                    aceStatus.text = "Для поиска P2P установите и запустите Ace Stream Engine на ТВ."
                }
            }
        }.start()
    }

    private fun addAceTile(title: String, infohash: String) {
        addTile(title, "acestream:?infohash=$infohash")
    }

    private fun startAce(infohash: String) {
        Toast.makeText(this, "Открываем Ace Stream…", Toast.LENGTH_SHORT).show()
        Thread {
            val contentId = runCatching {
                val url = "http://localhost:6878/server/api?api_version=3&method=get_content_id&infohash=$infohash"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_000
                    readTimeout = 3_000
                }
                try {
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        .getJSONObject("result").getString("content_id")
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (contentId.isNullOrBlank()) {
                    Toast.makeText(this, "Ace Stream не вернул адрес эфира.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                launchAceContentId(contentId)
            }
        }.start()
    }

    private fun launchAceContentId(contentId: String) {
        if (!contentId.matches(Regex("[a-fA-F0-9]{40}"))) return
        startActivity(Intent(this, AcePlaybackActivity::class.java)
            .putExtra(AcePlaybackActivity.EXTRA_CONTENT_ID, contentId.lowercase()))
    }

    private fun extractMatchLinks(html: String): List<Pair<String, String>> {
        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) as Spanned
        return spanned.getSpans(0, spanned.length, URLSpan::class.java)
            .mapNotNull { span ->
                val title = spanned.subSequence(spanned.getSpanStart(span), spanned.getSpanEnd(span))
                    .toString().replace(Regex("\\s+"), " ").trim()
                val uri = runCatching { Uri.parse(span.url) }.getOrNull() ?: return@mapNotNull null
                val url = if (uri.isRelative) Uri.parse("https://matchtv.ru").buildUpon()
                    .encodedPath(uri.encodedPath).build().toString() else span.url
                if (!title.contains("Спартак", ignoreCase = true) ||
                    !url.startsWith("https://matchtv.ru/") || !url.contains("matchtvvideo_")) null
                else title to url
            }
            .distinctBy { it.second }
            .take(12)
    }

    private fun addTile(title: String, url: String) {
        val button = Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 18f
            setTextColor(getColor(R.color.foreground))
            setBackgroundResource(R.drawable.bg_button)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                try {
                    if (url.startsWith("acestream:")) {
                        if (url.contains("content_id=")) launchAceContentId(url.substringAfter("content_id="))
                        else startAce(url.substringAfter("infohash="))
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } catch (_: ActivityNotFoundException) {
                    if (url.startsWith("acestream:")) {
                        Toast.makeText(this@BroadcastsActivity,
                            "Установите Ace Stream на телевизор для P2P-эфиров.", Toast.LENGTH_LONG).show()
                    } else {
                        startActivity(Intent(this@BroadcastsActivity, BroadcastWebActivity::class.java)
                            .putExtra(BroadcastWebActivity.EXTRA_URL, url))
                    }
                }
            }
        }
        tiles.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72).apply {
            bottomMargin = 12
        })
    }

    private companion object {
        const val CATALOG_URL = "https://matchtv.ru/football/club/spartak-moscow/broadcasts"
        const val P2P_DIRECTORY_URL = "https://acestreamid.com/channel/match-premyer"
    }
}
