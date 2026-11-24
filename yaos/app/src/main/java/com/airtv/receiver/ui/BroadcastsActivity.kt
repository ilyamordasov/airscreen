package com.airtv.receiver.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.Spanned
import android.text.TextUtils
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.airtv.receiver.R
import com.airtv.receiver.link.ProgrammeGuide
import com.airtv.receiver.link.ProgrammeGuideClient
import com.airtv.receiver.link.ProgrammeGuideClient.Channel
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** TV-side directory of football broadcasts and channel pages. */
class BroadcastsActivity : Activity() {
    private lateinit var carousel: TvPosterCarousel<String>
    private lateinit var status: TextView
    private lateinit var aceStatus: TextView
    private lateinit var directoryStatus: TextView
    private var loadGeneration = 0
    private lateinit var catalogueStatus: LinearLayout
    private lateinit var guideRow: LinearLayout
    private lateinit var nowLabel: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nextLabel: TextView
    private lateinit var nextTitle: TextView
    private val guide = ProgrammeGuideClient()
    private val guides = mutableMapOf<Channel, List<ProgrammeGuide.Entry>>()
    private val guideWorker = Executors.newFixedThreadPool(Channel.entries.size)
    private val guideTasks = mutableListOf<Future<*>>()
    private val guideHandler = Handler(Looper.getMainLooper())
    private var guideGeneration = 0
    private var guideRequestedAt: Long? = null
    private val guideTick = object : Runnable {
        override fun run() {
            refreshGuide()
            renderGuide()
            guideHandler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val share = TvChrome.action(this, getString(R.string.dashboard_send_link)).apply {
            id = View.generateViewId()
            setOnClickListener { startActivity(Intent(this@BroadcastsActivity, LinkShareActivity::class.java)) }
        }
        val refresh = TvChrome.action(this, getString(R.string.action_refresh)).apply {
            id = View.generateViewId()
            setOnClickListener { load() }
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(share)
            addView(refresh, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        val metrics = resources.displayMetrics
        val scale = minOf(metrics.widthPixels / 960f, metrics.heightPixels / 540f)
        carousel = TvPosterCarousel(this, scale, dp(4), showDots = false, focusUpId = share.id, open = ::openBroadcast)
        val compact = resources.configuration.screenHeightDp < 440
        status = TvChrome.label(this, "", if (compact) 12f else 14f, muted = true)
        aceStatus = TvChrome.label(this, "", if (compact) 11f else 13f, muted = true)
        directoryStatus = TvChrome.label(this, "", if (compact) 11f else 13f, muted = true)
        catalogueStatus = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(LinearLayout(this@BroadcastsActivity).apply {
                isBaselineAligned = false
                addView(aceStatus, LinearLayout.LayoutParams(0, -2, 1f))
                addView(directoryStatus, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        guideRow = LinearLayout(this).apply { isBaselineAligned = false }
        nowLabel = TvChrome.label(this, "", if (compact) 12f else 14f, muted = true)
        nextLabel = TvChrome.label(this, "", if (compact) 12f else 14f, muted = true)
        nowTitle = programmeLabel(compact)
        nextTitle = programmeLabel(compact)
        addGuideColumn(nowLabel, nowTitle)
        addGuideColumn(nextLabel, nextTitle)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(carousel, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(catalogueStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(guideRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        carousel.onSelectionChanged = { id ->
            share.nextFocusDownId = id
            refresh.nextFocusDownId = id
            renderGuide()
        }
        setContentView(TvChrome.page(this, TvChrome.Section.BROADCASTS,
            getString(R.string.dashboard_broadcasts), null, content, titleActions = actions))
        load()
    }

    override fun onResume() {
        super.onResume()
        guideHandler.post(guideTick)
    }

    override fun onPause() {
        guideHandler.removeCallbacks(guideTick)
        super.onPause()
    }

    override fun onDestroy() {
        guideWorker.shutdownNow()
        super.onDestroy()
    }

    private fun programmeLabel(compact: Boolean) = TvChrome.label(this, "", if (compact) 15f else 18f).apply {
        setLines(2)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun addGuideColumn(label: TextView, title: TextView) {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        guideRow.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (guideRow.childCount > 0) marginStart = dp(24)
        })
    }

    private fun refreshGuide(force: Boolean = false) {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && guideRequestedAt?.let { elapsed - it < GUIDE_REFRESH_INTERVAL } == true) return
        guideRequestedAt = elapsed
        val generation = ++guideGeneration
        guideTasks.forEach { it.cancel(true) }
        guideTasks.clear()
        val now = System.currentTimeMillis()
        for (channel in Channel.entries) {
            guideTasks += guideWorker.submit {
                val result = runCatching { guide.load(channel, now) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed || isFinishing || generation != guideGeneration) return@runOnUiThread
                    if (!result.isNullOrEmpty() || channel !in guides) guides[channel] = result.orEmpty()
                    renderGuide()
                }
            }
        }
    }

    private fun renderGuide() {
        val channel = Channel.entries.firstOrNull { it.page == carousel.selectedKey }
        guideRow.visibility = if (channel == null) View.GONE else View.VISIBLE
        catalogueStatus.visibility = if (channel == null) View.VISIBLE else View.GONE
        if (channel == null) return
        val now = System.currentTimeMillis()
        val window = ProgrammeGuide.window(guides[channel].orEmpty(), now)
        val remaining = window.current?.remainingMinutes(now)
        nowLabel.show(when {
            remaining == null -> getString(R.string.broadcast_guide_now)
            remaining < 60 -> getString(R.string.broadcast_guide_minutes, remaining)
            else -> getString(R.string.broadcast_guide_hours, remaining / 60, remaining % 60)
        })
        nowTitle.show(window.current?.title ?: getString(
            if (channel in guides) R.string.broadcast_guide_unavailable else R.string.broadcast_guide_loading))
        nextLabel.show(window.next?.let { getString(R.string.broadcast_guide_next_at, ProgrammeGuide.moscowClock(it.startsAt)) }
            ?: getString(R.string.broadcast_guide_next))
        nextTitle.show(window.next?.title ?: getString(R.string.broadcast_guide_no_next))
    }

    private fun TextView.show(value: String) {
        if (text != value) text = value
    }

    private fun load() {
        val generation = ++loadGeneration
        carousel.replace(listOf(
            TvPosterCarousel.Poster(Channel.MATCH_PREMIER.page, getString(R.string.broadcast_match_premier),
                getString(R.string.broadcast_tvcrate), R.drawable.dashboard_broadcasts, tint = TvGlassDrawable.PINK,
                actionSymbol = "▶"),
            TvPosterCarousel.Poster(Channel.OKKO_FOOTBALL.page, getString(R.string.broadcast_okko_football),
                getString(R.string.broadcast_tvcrate), R.drawable.catalog_football, tint = TvGlassDrawable.BLUE,
                actionSymbol = "▶"),
            TvPosterCarousel.Poster(Channel.OKKO_PRIME.page, getString(R.string.broadcast_okko_prime),
                getString(R.string.broadcast_tvcrate), R.drawable.catalog_live, actionSymbol = "▶"),
            TvPosterCarousel.Poster(CATALOG_URL, getString(R.string.broadcast_spartak),
                getString(R.string.broadcast_match_tv_source), R.drawable.catalog_football, tint = TvGlassDrawable.PINK),
        ))
        if (generation == 1) carousel.focus(Channel.MATCH_PREMIER.page)
        else refreshGuide(force = true)
        status.text = "Загружаем матчи с сайта Матч ТВ…"
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
                    links.forEach { (title, url) ->
                        carousel.append(TvPosterCarousel.Poster(url, title, getString(R.string.broadcast_match_tv_source),
                            R.drawable.catalog_football, tint = TvGlassDrawable.PINK))
                    }
                    status.text = if (links.isEmpty()) "Дополнительные матчи с сайта Матч ТВ недоступны." else
                        "Матчи и записи — источник: Матч ТВ"
                }.onFailure {
                    status.text = "Не удалось загрузить дополнительные матчи с сайта Матч ТВ."
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
                        addP2pPoster("acestream:?content_id=$id",
                            getString(R.string.broadcast_p2p_variant, index + 1), getString(R.string.broadcast_p2p_unverified))
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
                        val source = if (availability == 2) R.string.broadcast_ace_available else R.string.broadcast_ace
                        addP2pPoster("acestream:?infohash=$hash", name, getString(source))
                    }
                    aceStatus.text = if (streams.isEmpty()) "Ace Stream: эфиры «Спартака» не найдены." else
                        "Ace Stream: ${streams.size} P2P-эфиров"
                }.onFailure {
                    aceStatus.text = "Для поиска P2P установите и запустите Ace Stream Engine на ТВ."
                }
            }
        }.start()
    }

    private fun addP2pPoster(url: String, title: String, description: String) {
        carousel.append(TvPosterCarousel.Poster(url, title.ifBlank { getString(R.string.broadcast_match_premier) },
            description, R.drawable.catalog_live, tint = TvGlassDrawable.BLUE, actionSymbol = "▶"))
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

    private fun openBroadcast(url: String) {
        if (url.startsWith("acestream:")) {
            if (url.contains("content_id=")) launchAceContentId(url.substringAfter("content_id="))
            else startAce(url.substringAfter("infohash="))
        } else {
            startActivity(Intent(this, BroadcastWebActivity::class.java)
                .putExtra(BroadcastWebActivity.EXTRA_URL, url))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val GUIDE_REFRESH_INTERVAL = 5 * 60_000L
        const val CATALOG_URL = "https://matchtv.ru/football/club/spartak-moscow/broadcasts"
        const val P2P_DIRECTORY_URL = "https://acestreamid.com/channel/match-premyer"
    }
}
