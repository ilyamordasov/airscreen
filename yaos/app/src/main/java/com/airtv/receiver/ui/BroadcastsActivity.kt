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
import com.airtv.receiver.R
import java.net.HttpURLConnection
import java.net.URL

/** TV-side directory of official Spartak football broadcasts and channel pages. */
class BroadcastsActivity : Activity() {
    private lateinit var tiles: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcasts)
        tiles = findViewById(R.id.broadcasts_tiles)
        status = findViewById(R.id.broadcasts_status)
        findViewById<Button>(R.id.broadcasts_refresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        tiles.removeAllViews()
        addTile("Матч ТВ — прямой эфир", "https://matchtv.ru/video/channel/matchtv")
        addTile("Матч Премьер", "https://matchtv.ru/video/channel/premier")
        addTile("Все трансляции Спартака", CATALOG_URL)
        status.text = "Обновляем список матчей…"
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
                if (isFinishing || isDestroyed) return@runOnUiThread
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
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(this@BroadcastsActivity, BroadcastWebActivity::class.java)
                        .putExtra(BroadcastWebActivity.EXTRA_URL, url))
                }
            }
        }
        tiles.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72).apply {
            bottomMargin = 12
        })
    }

    private companion object {
        const val CATALOG_URL = "https://matchtv.ru/football/club/spartak-moscow/broadcasts"
    }
}
