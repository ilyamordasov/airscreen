package com.airtv.receiver.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.airtv.receiver.link.MediaCandidate
import com.airtv.receiver.link.SharedPage
import com.airtv.receiver.link.TvCrateHttpException
import com.airtv.receiver.link.TvCrateResolver
import com.airtv.receiver.link.TvCrateStream
import com.airtv.receiver.vpn.FreeCastVpnService
import com.airtv.receiver.vpn.VpnProfileStore
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** TV-owned HLS/MP4 playback; TVCrate page links are resolved on the TV before playback. */
@OptIn(UnstableApi::class)
class StreamPlaybackActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var openPage: Button
    private val main = Handler(Looper.getMainLooper())
    private val resolverExecutor = Executors.newSingleThreadExecutor()
    private var resolveJob: Future<*>? = null
    private var generation = 0
    private var active = false
    private var directMediaUrl: String? = null
    private var sourcePage: String? = null
    private var tvCratePage: String? = null
    private var resumePosition = 0L
    private var resumePlayback = true
    private var automaticRecoveryUsed = false
    private var vpnPermissionRequested = false
    private var vpnPermissionPending = false
    private var revealKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private val refreshStream = Runnable {
        if (active && tvCratePage != null) resolveTvCrate(fresh = true, silent = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directMediaUrl = intent.getStringExtra(EXTRA_MEDIA_URL)?.let(MediaCandidate::parse)
        sourcePage = intent.getStringExtra(EXTRA_SOURCE_PAGE)?.let(SharedPage::parse)
        tvCratePage = sourcePage?.takeIf(TvCrateResolver::supports)
        if (directMediaUrl == null && tvCratePage == null) { finish(); return }
        enterFullscreen()

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            controllerShowTimeoutMs = 3_000
            controllerAutoShow = false
            controllerHideOnTouch = true
            // Immediate hiding keeps Back and the three-second timeout deterministic on a TV remote.
            setControllerAnimationEnabled(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
            hideController()
        }
        status = TextView(this).apply {
            text = "Подключаемся к видеопотоку…"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(24, 18, 24, 18)
        }
        retry = Button(this).apply {
            text = "Повторить"
            visibility = View.GONE
            isFocusableInTouchMode = true
            setOnClickListener {
                automaticRecoveryUsed = false
                vpnPermissionRequested = false
                resumePlayback = true
                player?.playWhenReady = true
                if (tvCratePage != null) resolveTvCrate(fresh = true)
                else directMediaUrl?.let { play(it) }
            }
        }
        openPage = Button(this).apply {
            text = "Открыть страницу"
            visibility = View.GONE
            setOnClickListener {
                sourcePage?.let { page ->
                    startActivity(Intent(this@StreamPlaybackActivity, BroadcastWebActivity::class.java)
                        .putExtra(BroadcastWebActivity.EXTRA_URL, page)
                        .putExtra(BroadcastWebActivity.EXTRA_FORCE_WEB, true))
                    finish()
                }
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(retry)
            if (sourcePage != null && sourcePage != directMediaUrl) addView(openPage)
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(actions)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        })
    }

    override fun onStart() {
        super.onStart()
        if (!::playerView.isInitialized) return
        active = true
        automaticRecoveryUsed = false
        if (tvCratePage != null) resolveTvCrate(fresh = false)
        else directMediaUrl?.let { play(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) enterFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) revealKeyCode = KeyEvent.KEYCODE_UNKNOWN
        if (hasFocus && !isFinishing) enterFullscreen()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun resolveTvCrate(fresh: Boolean, silent: Boolean = false) {
        val page = tvCratePage ?: return
        if (!active || isFinishing || isDestroyed) return
        val requestGeneration = ++generation
        resolveJob?.cancel(true)
        main.removeCallbacks(refreshStream)
        val useVpn = VpnProfileStore.load(this) != null
        if (useVpn && !ensureVpnStarted(silent)) return
        if (!silent) {
            showLoading(if (useVpn && FreeCastVpnService.connectionState != FreeCastVpnService.State.CONNECTED) {
                "Подключаем VPN…"
            } else {
                "Получаем адрес трансляции TVCrate…"
            })
        }
        resolveJob = resolverExecutor.submit {
            val result = runCatching {
                if (useVpn) {
                    awaitVpn()
                    if (!silent) main.post {
                        if (active && !isFinishing && !isDestroyed && requestGeneration == generation) {
                            showLoading("Получаем адрес трансляции TVCrate…")
                        }
                    }
                }
                TvCrateResolver().resolve(page, fresh)
            }
            main.post {
                if (!active || isFinishing || isDestroyed || requestGeneration != generation) return@post
                resolveJob = null
                result.onSuccess { stream ->
                    play(stream.mediaUrl, stream)
                    scheduleRefresh(stream.expiresAtEpochSeconds)
                }.onFailure { error ->
                    val http = (error as? TvCrateHttpException)?.statusCode
                    Log.w(TAG, "TVCrate lookup failed (${error.javaClass.simpleName}, HTTP=$http, refresh=$silent)")
                    // A proactive refresh failure must not interrupt a still-valid running stream.
                    if (!silent || player == null || player?.playerError != null) {
                        showError(when (error) {
                            is VpnConnectionException -> "Не удалось подключить VPN. Повторите попытку или проверьте профиль в разделе VPN."
                            is TvCrateHttpException -> error.message.orEmpty()
                            is SocketTimeoutException, is UnknownHostException -> "Сервер TVCrate не ответил. Повторите попытку."
                            else -> "Не удалось получить трансляцию TVCrate. Повторите попытку или откройте страницу."
                        })
                    }
                }
            }
        }
    }

    private fun ensureVpnStarted(silent: Boolean): Boolean {
        val permission = VpnService.prepare(this)
        if (permission == null) {
            FreeCastVpnService.connect(this)
            return true
        }
        if (silent || vpnPermissionPending) return false
        if (vpnPermissionRequested) {
            showError("Для TVCrate разрешите подключение VPN. Нажмите «Повторить».")
            return false
        }
        vpnPermissionRequested = true
        vpnPermissionPending = true
        showLoading("Подтвердите подключение VPN…")
        try {
            startActivityForResult(permission, VPN_PERMISSION_REQUEST)
        } catch (_: ActivityNotFoundException) {
            vpnPermissionPending = false
            showError("Android не открыл запрос на подключение VPN. Проверьте настройки VPN.")
        }
        return false
    }

    @Deprecated("Android VPN consent uses startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_PERMISSION_REQUEST) return
        vpnPermissionPending = false
        if (active) resolveTvCrate(fresh = false)
    }

    private fun awaitVpn() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (FreeCastVpnService.connectionState == FreeCastVpnService.State.CONNECTING &&
            SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
        if (FreeCastVpnService.connectionState != FreeCastVpnService.State.CONNECTED) throw VpnConnectionException()
    }

    private class VpnConnectionException : Exception()

    private fun play(url: String, stream: TvCrateStream? = null) {
        if (!active || isFinishing || isDestroyed) return
        player?.let { resumePlayback = it.playWhenReady }
        releasePlayer()
        showLoading("Подключаемся к видеопотоку…")
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        if (stream != null) {
            http.setUserAgent(TvCrateResolver.USER_AGENT)
                .setDefaultRequestProperties(stream.requestHeaders)
        }
        val next = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        player = next
        playerView.player = next
        playerView.useController = true
        next.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player === next && active) status.visibility = View.GONE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === next) updateKeepScreenOn()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player === next) updateKeepScreenOn()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== next || !active || isFinishing || isDestroyed) return
                Log.w(TAG, "Playback failed (${error.errorCodeName})")
                updateKeepScreenOn()
                if (tvCratePage != null && !automaticRecoveryUsed && isExpiredResponse(error)) {
                    // One automatic recovery per playback attempt, so unavailable sources cannot loop.
                    automaticRecoveryUsed = true
                    resolveTvCrate(fresh = true)
                } else {
                    showError("Поток недоступен на ТВ (${error.errorCodeName}).")
                }
            }
        })
        val media = MediaItem.Builder().setUri(url).apply {
            if (stream != null || url.substringBefore('?').endsWith(".m3u8", true)) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }.build()
        next.setMediaItem(media)
        if (stream == null && resumePosition > 0L) next.seekTo(resumePosition)
        next.prepare()
        next.playWhenReady = resumePlayback
        updateKeepScreenOn()
        playerView.hideController()
        playerView.requestFocus()
    }

    private fun scheduleRefresh(expiresAtEpochSeconds: Long?) {
        main.removeCallbacks(refreshStream)
        val expires = expiresAtEpochSeconds ?: return
        val secondsRemaining = expires - System.currentTimeMillis() / 1_000
        // An already-expiring response falls back to bounded HTTP-error recovery, not a hot timer loop.
        if (secondsRemaining <= 60) return
        val delayMillis = (secondsRemaining - 60).coerceIn(30L, 6L * 60 * 60) * 1_000
        main.postDelayed(refreshStream, delayMillis)
    }

    private fun showLoading(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
        retry.visibility = View.GONE
        openPage.visibility = View.GONE
    }

    private fun showError(message: String) {
        main.removeCallbacks(refreshStream)
        playerView.hideController()
        status.text = message
        status.visibility = View.VISIBLE
        retry.visibility = View.VISIBLE
        if (sourcePage != null && sourcePage != directMediaUrl) openPage.visibility = View.VISIBLE
        retry.requestFocus()
    }

    private fun isExpiredResponse(error: PlaybackException): Boolean =
        generateSequence(error as Throwable) { it.cause }.take(8)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode == 403 || it.responseCode == 410 }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let Activity deliver Back to onBackPressed, rather than to a focused player control.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (!::playerView.isInitialized) return super.dispatchKeyEvent(event)
        if (revealKeyCode != KeyEvent.KEYCODE_UNKNOWN && event.keyCode == revealKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) revealKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (retry.visibility == View.VISIBLE || openPage.visibility == View.VISIBLE) {
            return super.dispatchKeyEvent(event)
        }
        if (player == null) return super.dispatchKeyEvent(event)
        val select = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (select && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            // First OK reveals controls; consume its key-up too, so it cannot also pause the video.
            revealKeyCode = event.keyCode
            playerView.showController()
            return true
        }
        // Media keys and subsequent OK presses retain Media3's normal remote behaviour.
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    @Deprecated("Back hides playback controls before leaving this screen.")
    override fun onBackPressed() {
        if (::playerView.isInitialized && playerView.isControllerFullyVisible) {
            playerView.hideController()
            playerView.requestFocus()
        } else {
            super.onBackPressed()
        }
    }

    private fun updateKeepScreenOn() {
        if (!::playerView.isInitialized) return
        val current = player
        playerView.keepScreenOn = active && current != null && current.playWhenReady &&
            current.playerError == null &&
            (current.playbackState == Player.STATE_READY || current.playbackState == Player.STATE_BUFFERING)
    }

    override fun onStop() {
        active = false
        revealKeyCode = KeyEvent.KEYCODE_UNKNOWN
        ++generation
        resolveJob?.cancel(true)
        resolveJob = null
        main.removeCallbacks(refreshStream)
        player?.let {
            resumePlayback = it.playWhenReady
            resumePosition = it.currentPosition
        }
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        active = false
        ++generation
        resolveJob?.cancel(true)
        resolverExecutor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        if (::playerView.isInitialized) {
            // Media3 would otherwise reveal its controller indefinitely without a player.
            playerView.useController = false
            playerView.player = null
            playerView.keepScreenOn = false
        }
        previous?.release()
    }

    companion object {
        private const val TAG = "StreamPlaybackActivity"
        private const val VPN_PERMISSION_REQUEST = 815
        const val EXTRA_MEDIA_URL = "com.airtv.receiver.MEDIA_URL"
        const val EXTRA_SOURCE_PAGE = "com.airtv.receiver.SOURCE_PAGE"
    }
}
