package com.airtv.receiver.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.airtv.receiver.BuildConfig
import com.airtv.receiver.R
import com.airtv.receiver.link.AceCatalogue
import com.airtv.receiver.link.AcePlaybackSession
import com.airtv.receiver.link.AceSource
import com.airtv.receiver.link.AceStreamEngine
import com.airtv.receiver.link.AceStreamProbe
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** The programme guide and the independent P2P discovery sources have separate screens. */
class P2pBroadcastsActivity : Activity() {
    private lateinit var carousel: TvPosterCarousel<AceSource>
    private lateinit var engineStatus: TextView
    private lateinit var directoryStatus: TextView
    private lateinit var retry: Button
    private lateinit var inspect: Button
    private val workers = Executors.newFixedThreadPool(2)
    private val probeWorker = Executors.newSingleThreadExecutor()
    private val checkedCards = mutableMapOf<AceSource, String>()
    private var directory = emptyList<AceCatalogue.DirectoryEntry>()
    private var search = emptyList<AceCatalogue.SearchEntry>()
    private var pending = 0
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retry = TvChrome.action(this, getString(R.string.p2p_retry_search)).apply {
            id = View.generateViewId()
            setOnClickListener { load() }
        }
        inspect = TvChrome.action(this, getString(R.string.p2p_check_stream)).apply {
            id = View.generateViewId()
            isEnabled = false
            setOnClickListener { carousel.selectedKey?.let(::checkStream) }
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(retry)
            addView(inspect, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = TvChrome.dp(this@P2pBroadcastsActivity, 8)
            })
        }
        val metrics = resources.displayMetrics
        val scale = minOf(metrics.widthPixels / 960f, metrics.heightPixels / 540f)
        carousel = TvPosterCarousel(this, scale, TvChrome.dp(this, 4), showDots = false, focusUpId = inspect.id) {
            if (!checking) startActivity(AcePlaybackActivity.intent(this, it))
        }
        carousel.onSelectionChanged = {
            retry.nextFocusDownId = it
            inspect.nextFocusDownId = it
        }
        engineStatus = TvChrome.label(this, "", 16f, muted = true)
        directoryStatus = TvChrome.label(this, "", 16f, muted = true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(engineStatus)
            addView(directoryStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvChrome.dp(this@P2pBroadcastsActivity, 6) })
            addView(carousel, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(TvChrome.page(this, TvChrome.Section.BROADCASTS,
            getString(R.string.p2p_title), null, content, titleActions = actions))
        retry.requestFocus()
        load()
    }

    override fun onDestroy() {
        workers.shutdownNow()
        probeWorker.shutdownNow()
        super.onDestroy()
    }

    private fun checkStream(source: AceSource) {
        if (checking) return
        checking = true
        inspect.setText(R.string.p2p_check_in_progress)
        checkedCards[source] = getString(R.string.p2p_check_in_progress)
        carousel.setDescription(source, checkedCards.getValue(source))
        probeWorker.execute {
            val port = runCatching { AceStreamEngine.port(this) }
            val result = port.mapCatching {
                val session = AcePlaybackSession.open(it, source, timeout = 12_000)
                try {
                    AceStreamProbe.measure(this, session)
                } finally {
                    runCatching { session.stop() }.onFailure { failure ->
                        Log.d(TAG, "Probe stop failed (${failure.javaClass.simpleName})")
                    }
                }
            }
            val description = result.fold(::describeProbe) {
                Log.w(TAG, "Stream check failed (${it.javaClass.simpleName})")
                val engineLost = it is ConnectException || port.getOrNull()?.let { p ->
                    !AceStreamEngine.isReady(p)
                } == true
                getString(when {
                    it is AcePlaybackSession.PremiumRequired -> R.string.p2p_check_premium
                    engineLost || port.isFailure -> R.string.p2p_check_engine_unavailable
                    else -> R.string.p2p_check_no_video
                })
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                checking = false
                checkedCards[source] = description
                carousel.setDescription(source, description)
                inspect.setText(R.string.p2p_check_stream)
            }
        }
    }

    private fun describeProbe(result: AceStreamProbe.Result): String {
        val quality = listOfNotNull(
            result.video.height?.let { getString(R.string.p2p_check_resolution, it) },
            result.video.bitrate?.let { getString(R.string.p2p_check_bitrate, it / 1_000_000.0) },
        ).joinToString(" · ").ifEmpty { getString(R.string.p2p_check_unknown_quality) }
        val network = listOfNotNull(
            result.stats?.downloadKilobytesPerSecond?.let { getString(R.string.p2p_check_download, it) },
            result.stats?.peers?.let { getString(R.string.p2p_check_peers, it) },
        ).joinToString(" · ").ifEmpty { getString(R.string.p2p_check_unknown_speed) }
        return "$quality\n$network"
    }

    private fun load() {
        if (pending != 0) return
        pending = 2
        retry.setText(R.string.p2p_search_in_progress)
        engineStatus.setText(R.string.p2p_engine_loading)
        directoryStatus.setText(R.string.p2p_directory_loading)
        workers.execute {
            val result = runCatching {
                val connection = (URL(DIRECTORY).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 7_000
                }
                AceCatalogue.directory(AceCatalogue.read(connection))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    directory = it
                    directoryStatus.text = if (it.isEmpty()) getString(R.string.p2p_directory_empty)
                        else getString(R.string.p2p_directory_count, it.size)
                }.onFailure {
                    Log.w(TAG, "Directory request failed (${it.javaClass.simpleName})")
                    directoryStatus.setText(R.string.p2p_directory_unavailable)
                }
                complete()
            }
        }
        workers.execute {
            val result = runCatching { AceStreamEngine.port(this) }
            if (result.isFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    engineStatus.setText(if (BuildConfig.EMBEDDED_ACE) R.string.p2p_engine_unavailable
                        else R.string.p2p_external_engine_unavailable)
                    complete()
                }
                return@execute
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) engineStatus.setText(R.string.p2p_engine_searching)
            }
            val results = listOf("Матч Премьер", "Спартак").map { query ->
                runCatching {
                    val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
                    val connection = AceStreamEngine.connection(result.getOrThrow(), "/search?query=$encoded&page_size=20").apply {
                        connectTimeout = 2_000
                        readTimeout = 9_000
                    }
                    AceCatalogue.search(JSONObject(AceCatalogue.read(connection)))
                }.onFailure { Log.w(TAG, "Ace Search request failed (${it.javaClass.simpleName})") }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val successful = results.mapNotNull { it.getOrNull() }
                if (successful.isNotEmpty()) search = successful.flatten().distinctBy { it.source }.take(12)
                engineStatus.text = when {
                    successful.isEmpty() -> getString(R.string.p2p_search_unavailable)
                    search.isEmpty() -> getString(R.string.p2p_search_empty)
                    else -> getString(R.string.p2p_search_count, search.size)
                }
                complete()
            }
        }
    }

    private fun complete() {
        pending--
        if (pending == 0) retry.setText(R.string.p2p_retry_search)
        val posters = search.map {
            poster(it.source, it.title.ifBlank { getString(R.string.p2p_stream) }, getString(R.string.p2p_search_source))
        } + directory.mapIndexed { index, entry ->
            val label = when (entry.report) {
                AceCatalogue.Report.WORKING -> R.string.p2p_directory_working
                AceCatalogue.Report.BROKEN -> R.string.p2p_directory_broken
                AceCatalogue.Report.UNKNOWN -> R.string.p2p_directory_unverified
            }
            poster(entry.source, getString(R.string.broadcast_p2p_variant, index + 1), getString(label))
        }
        carousel.replace(posters)
        inspect.isEnabled = posters.isNotEmpty()
    }

    private fun poster(source: AceSource, title: String, description: String) = TvPosterCarousel.Poster(
        source, title, checkedCards[source] ?: "$description\n${getString(R.string.p2p_check_not_run)}",
        R.drawable.catalog_live, tint = TvGlassDrawable.BLUE, actionSymbol = "▶",
    )

    private companion object {
        const val TAG = "P2pCatalogue"
        const val DIRECTORY = "https://acestreamid.com/channel/match-premyer"
    }
}
