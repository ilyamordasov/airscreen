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
import com.airtv.receiver.R
import com.airtv.receiver.link.ProgrammeGuide
import com.airtv.receiver.link.ProgrammeGuideClient
import com.airtv.receiver.link.ProgrammeGuideClient.Channel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** TV-side directory of football broadcasts and channel pages. */
class BroadcastsActivity : Activity() {
    private lateinit var carousel: TvPosterCarousel<String>
    private lateinit var status: TextView
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
        catalogueStatus = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
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
        catalogueStatus.visibility = if (channel == null && carousel.selectedKey != P2P) View.VISIBLE else View.GONE
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
                getString(R.string.broadcast_tvcrate), R.drawable.catalog_okko, tint = TvGlassDrawable.BLUE,
                actionSymbol = "▶"),
            TvPosterCarousel.Poster(Channel.OKKO_PRIME.page, getString(R.string.broadcast_okko_prime),
                getString(R.string.broadcast_tvcrate), R.drawable.catalog_okko, actionSymbol = "▶"),
            TvPosterCarousel.Poster(P2P, getString(R.string.p2p_title),
                getString(R.string.p2p_card_description), R.drawable.catalog_live, tint = TvGlassDrawable.BLUE),
            TvPosterCarousel.Poster(CATALOG_URL, getString(R.string.broadcast_spartak),
                getString(R.string.broadcast_match_tv_source), R.drawable.catalog_football, tint = TvGlassDrawable.PINK),
        ))
        if (generation == 1) carousel.focus(Channel.MATCH_PREMIER.page)
        else refreshGuide(force = true)
        status.text = "Загружаем матчи с сайта Матч ТВ…"
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
        if (url == P2P) {
            startActivity(Intent(this, P2pBroadcastsActivity::class.java))
        } else {
            startActivity(Intent(this, BroadcastWebActivity::class.java)
                .putExtra(BroadcastWebActivity.EXTRA_URL, url))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val GUIDE_REFRESH_INTERVAL = 5 * 60_000L
        const val CATALOG_URL = "https://matchtv.ru/football/club/spartak-moscow/broadcasts"
        const val P2P = "p2p"
    }
}
