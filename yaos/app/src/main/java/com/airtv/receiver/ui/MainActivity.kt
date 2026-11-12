/* FreeCast TV interface, 2026. GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md. */
package com.airtv.receiver.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.airtv.receiver.R
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.service.AirPlayService
import com.airtv.receiver.settings.ReceiverPreferences
import com.airtv.receiver.settings.ReceiverSettings
import kotlin.math.min

/**
 * TV controls around a permanently attached mirroring surface. The service owns the
 * connection and decoders; this activity presents their state and supplies the Surface.
 */
class MainActivity : Activity() {

    private lateinit var screenRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var receiverName: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var stateLabel: TextView
    private lateinit var statusIndicator: View
    private lateinit var statusProgress: View
    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var toggleButton: Button
    private lateinit var settingsButton: Button
    private lateinit var diagnosticsButton: Button
    private lateinit var broadcastsButton: Button
    private lateinit var resumeButton: Button
    private lateinit var playbackHint: View

    private var service: AirPlayService.LocalBinder? = null
    private var receiver: AirPlayReceiver? = null
    private var bound = false
    private var pendingSurface: SurfaceHolder? = null
    private var pendingSettings: ReceiverSettings? = null
    private var currentState: ReceiverState = ReceiverState.Stopped
    private var controlsRequested = false
    private var lastAddress: String? = null
    private var activeDialog: AlertDialog? = null
    private var diagnosticsText: TextView? = null
    private var resumed = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hidePlaybackHint = Runnable { playbackHint.visibility = View.GONE }
    private val connectingHint = Runnable {
        if (currentState is ReceiverState.Connecting) {
            detailsText.setText(R.string.detail_connecting_long)
        }
    }
    private val refreshDiagnostics = object : Runnable {
        override fun run() {
            if (!resumed || diagnosticsText == null || activeDialog?.isShowing != true) return
            updateDiagnostics()
            uiHandler.postDelayed(this, DIAGNOSTICS_INTERVAL_MS)
        }
    }

    private val stateListener: (ReceiverState) -> Unit = { next ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) render(next)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? AirPlayService.LocalBinder ?: return
            receiver?.removeListener(stateListener)
            service = local
            receiver = local.receiver
            pendingSurface?.surface?.takeIf { it.isValid }?.let(local.receiver::setSurface)
            local.receiver.addListener(stateListener)
            pendingSettings?.let {
                pendingSettings = null
                local.applySettings(it)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            receiver?.removeListener(stateListener)
            receiver = null
            service = null
            render(ReceiverState.Failed(getString(R.string.detail_service_disconnected)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        screenRoot = findViewById(R.id.screen_root)
        surfaceView = findViewById(R.id.surface)
        overlay = findViewById(R.id.overlay)
        receiverName = findViewById(R.id.receiver_name)
        connectionInfo = findViewById(R.id.connection_info)
        stateLabel = findViewById(R.id.state_label)
        statusIndicator = findViewById(R.id.status_indicator)
        statusProgress = findViewById(R.id.status_progress)
        statusText = findViewById(R.id.status)
        detailsText = findViewById(R.id.details)
        toggleButton = findViewById(R.id.toggle_receiver)
        settingsButton = findViewById(R.id.settings_button)
        diagnosticsButton = findViewById(R.id.diagnostics_button)
        broadcastsButton = findViewById(R.id.broadcasts_button)
        resumeButton = findViewById(R.id.resume_button)
        playbackHint = findViewById(R.id.playback_hint)

        toggleButton.setOnClickListener { toggleReceiver() }
        settingsButton.setOnClickListener { showSettings() }
        diagnosticsButton.setOnClickListener { showDiagnostics() }
        broadcastsButton.setOnClickListener { startActivity(Intent(this, BroadcastsActivity::class.java)) }
        resumeButton.setOnClickListener { hideControls() }
        findViewById<View>(R.id.exit_button).apply {
            setOnClickListener { requestExit() }
            nextFocusDownId = R.id.settings_button
        }
        listOf(toggleButton, settingsButton, diagnosticsButton, broadcastsButton, resumeButton).forEach {
            it.nextFocusUpId = R.id.exit_button
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                pendingSurface = holder
                receiver?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingSurface = null
                receiver?.setSurface(null)
            }
        })

        render(ReceiverState.Starting)
        startAndBind()
        settingsButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (diagnosticsText != null) uiHandler.post(refreshDiagnostics)
    }

    override fun onPause() {
        resumed = false
        uiHandler.removeCallbacks(refreshDiagnostics)
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        activeDialog?.dismiss()
        activeDialog = null
        receiver?.removeListener(stateListener)
        receiver?.setSurface(null)
        if (bound) runCatching { unbindService(connection) }
        bound = false
        if (isFinishing) AirPlayService.stop(this)
        super.onDestroy()
    }

    private fun startAndBind() {
        runCatching {
            AirPlayService.start(this)
            if (!bound) {
                bound = bindService(
                    Intent(this, AirPlayService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
                check(bound) { getString(R.string.detail_service_disconnected) }
            }
        }.onFailure {
            render(
                ReceiverState.Failed(
                    getString(R.string.detail_start_failed, it.localizedMessage.orEmpty()),
                ),
            )
        }
    }

    private fun toggleReceiver() {
        val local = service
        if (local == null) {
            render(ReceiverState.Starting)
            startAndBind()
            return
        }
        when (currentState) {
            ReceiverState.Starting -> Unit
            ReceiverState.Stopped -> local.startReceiver()
            is ReceiverState.Failed -> local.restartReceiver()
            else -> local.stopReceiver()
        }
    }

    /**
     * Streaming is emitted by the receiver only after decoded video is released to the
     * Surface. Connecting therefore keeps the instructions visible, even after RTSP setup.
     */
    private fun render(next: ReceiverState) {
        val previous = currentState
        currentState = next
        if (next !is ReceiverState.Streaming) controlsRequested = false
        if (next !is ReceiverState.Connecting) uiHandler.removeCallbacks(connectingHint)

        val busy = next is ReceiverState.Starting || next is ReceiverState.Connecting
        statusProgress.visibility = if (busy) View.VISIBLE else View.GONE
        statusIndicator.visibility = if (busy) View.GONE else View.VISIBLE
        val tint = when (next) {
            is ReceiverState.Failed -> R.color.error
            is ReceiverState.AudioOnly -> if (next.playable) R.color.accent else R.color.warning
            ReceiverState.Stopped -> R.color.muted
            else -> R.color.accent
        }
        statusIndicator.backgroundTintList = ColorStateList.valueOf(getColor(tint))
        stateLabel.setTextColor(getColor(tint))

        when (next) {
            ReceiverState.Starting -> {
                stateLabel.setText(R.string.state_starting)
                statusText.setText(R.string.status_starting)
                detailsText.setText(R.string.detail_starting)
            }
            ReceiverState.Stopped -> {
                stateLabel.setText(R.string.state_stopped)
                statusText.setText(R.string.status_stopped)
                detailsText.setText(R.string.detail_stopped)
            }
            is ReceiverState.Advertising -> {
                lastAddress = next.address
                stateLabel.setText(R.string.state_ready)
                statusText.setText(R.string.status_ready_heading)
                detailsText.setText(R.string.detail_ready)
            }
            is ReceiverState.Connecting -> {
                stateLabel.setText(R.string.state_connecting)
                statusText.text = getString(R.string.status_connecting, senderName(next.clientName))
                detailsText.setText(R.string.detail_connecting)
                if (previous !is ReceiverState.Connecting) {
                    uiHandler.postDelayed(connectingHint, CONNECTING_HINT_DELAY_MS)
                }
            }
            is ReceiverState.Streaming -> {
                stateLabel.setText(R.string.state_streaming)
                statusText.text = getString(R.string.status_streaming, senderName(next.clientName))
                detailsText.setText(R.string.detail_streaming)
                applyVideoAspect(next.width, next.height)
            }
            is ReceiverState.AudioOnly -> {
                stateLabel.setText(if (next.playable) R.string.state_audio else R.string.state_unsupported)
                if (next.playable) {
                    statusText.text = getString(R.string.status_audio_only, senderName(next.clientName))
                    detailsText.setText(R.string.status_audio_only_detail)
                } else {
                    statusText.setText(R.string.status_audio_unsupported_heading)
                    detailsText.text = getString(R.string.status_audio_unsupported_detail, next.codec)
                }
            }
            is ReceiverState.Failed -> {
                stateLabel.setText(R.string.state_error)
                statusText.setText(R.string.status_failed)
                detailsText.text = next.reason
            }
        }

        toggleButton.isEnabled = next !is ReceiverState.Starting
        toggleButton.alpha = if (toggleButton.isEnabled) 1f else 0.55f
        toggleButton.setText(
            when (next) {
                ReceiverState.Stopped -> R.string.action_start
                is ReceiverState.Failed -> R.string.action_retry
                else -> R.string.action_stop
            },
        )
        updateReceiverInfo()
        updateControls()
        if (next is ReceiverState.Streaming && previous !is ReceiverState.Streaming) {
            playbackHint.visibility = View.VISIBLE
            uiHandler.removeCallbacks(hidePlaybackHint)
            uiHandler.postDelayed(hidePlaybackHint, PLAYBACK_HINT_DURATION_MS)
        }
        updateDiagnostics()
    }

    private fun updateReceiverInfo() {
        val config = receiver?.settings ?: ReceiverPreferences.load(this)
        val name = when (val state = currentState) {
            is ReceiverState.Advertising -> state.name
            else -> receiver?.receiverName ?: config.name
        }
        receiverName.text = name
        receiverName.textSize = if (name.length > 32) 26f else 34f
        connectionInfo.text = listOfNotNull(
            lastAddress?.takeIf { it.isNotBlank() }?.let { getString(R.string.address_summary, it) },
            getString(R.string.profile_summary, config.height, config.fps),
        ).joinToString("\n")
    }

    private fun updateControls() {
        val streaming = currentState is ReceiverState.Streaming
        val showDashboard = !streaming || controlsRequested
        val wasVisible = overlay.visibility == View.VISIBLE
        overlay.visibility = if (showDashboard) View.VISIBLE else View.GONE
        resumeButton.visibility = if (streaming) View.VISIBLE else View.GONE
        broadcastsButton.nextFocusRightId = if (streaming) R.id.resume_button else R.id.exit_button
        if (showDashboard) {
            playbackHint.visibility = View.GONE
            if (!wasVisible) {
                if (streaming) resumeButton.requestFocus() else settingsButton.requestFocus()
            }
        } else {
            screenRoot.requestFocus()
        }
    }

    private fun showControls() {
        controlsRequested = true
        updateControls()
        resumeButton.requestFocus()
    }

    private fun hideControls() {
        if (currentState !is ReceiverState.Streaming) return
        controlsRequested = false
        updateControls()
        playbackHint.visibility = View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // TV remotes send hardware Back. Handle the complete key pair consistently across
        // platform versions; a dialog or the input keyboard owns its own window/events.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        val revealKeys = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_MENU ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (currentState is ReceiverState.Streaming && overlay.visibility != View.VISIBLE && revealKeys) {
            // Consume both halves of the key so opening the panel does not activate a button.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) showControls()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("TV remote Back is handled here for Android 7 and later.")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (currentState is ReceiverState.Streaming) {
            if (overlay.visibility == View.VISIBLE) hideControls() else showControls()
        } else {
            requestExit()
        }
    }

    private fun requestExit() {
        val active = currentState is ReceiverState.Streaming ||
            currentState is ReceiverState.Connecting ||
            currentState is ReceiverState.AudioOnly
        if (!active) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_FreeCast_Dialog)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setNegativeButton(R.string.action_continue, null)
            .setPositiveButton(R.string.action_exit) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
        openDialog(dialog, 580)
    }

    private fun showSettings() {
        val initial = ReceiverPreferences.load(this)
        val content = layoutInflater.inflate(R.layout.dialog_settings, null)
        val nameInput = content.findViewById<EditText>(R.id.settings_name)
        val resolution = content.findViewById<RadioGroup>(R.id.settings_resolution)
        val framerate = content.findViewById<RadioGroup>(R.id.settings_framerate)
        nameInput.setText(initial.name)
        resolution.check(if (initial.height == 720) R.id.quality_720 else R.id.quality_1080)
        framerate.check(if (initial.fps == 60) R.id.fps_60 else R.id.fps_30)
        nameInput.nextFocusDownId = resolution.checkedRadioButtonId

        val dialog = AlertDialog.Builder(this, R.style.Theme_FreeCast_Dialog)
            .setTitle(R.string.settings_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            nameInput.requestFocus()
            nameInput.selectAll()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosenName = nameInput.text.toString().trim()
                if (chosenName.isBlank()) {
                    nameInput.error = getString(R.string.settings_name_error)
                    nameInput.requestFocus()
                    return@setOnClickListener
                }
                val chosen = ReceiverSettings(
                    name = chosenName,
                    height = if (resolution.checkedRadioButtonId == R.id.quality_720) 720 else 1080,
                    fps = if (framerate.checkedRadioButtonId == R.id.fps_60) 60 else 30,
                )
                if (chosen != initial) {
                    runCatching {
                        val saved = ReceiverPreferences.save(this, chosen)
                        val local = service
                        if (local != null) local.applySettings(saved)
                        else {
                            pendingSettings = saved
                            startAndBind()
                        }
                        updateReceiverInfo()
                    }.onFailure {
                        Toast.makeText(
                            this,
                            getString(R.string.settings_save_failed, it.localizedMessage.orEmpty()),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
                hideKeyboard(nameInput)
                dialog.dismiss()
            }
        }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(nameInput)
                content.findViewById<View>(resolution.checkedRadioButtonId).requestFocus()
                true
            } else {
                false
            }
        }
        openDialog(dialog, 680)
    }

    private fun showDiagnostics() {
        val content = layoutInflater.inflate(R.layout.dialog_diagnostics, null)
        val text = content.findViewById<TextView>(R.id.diagnostics_content)
        val dialog = AlertDialog.Builder(this, R.style.Theme_FreeCast_Dialog)
            .setTitle(R.string.diagnostics_title)
            .setView(content)
            .setNeutralButton(R.string.action_refresh, null)
            .setNegativeButton(R.string.action_copy, null)
            .setPositiveButton(R.string.action_done, null)
            .create()
        dialog.setOnShowListener {
            diagnosticsText = text
            updateDiagnostics()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { updateDiagnostics() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.diagnostics_clip_label), text.text),
                )
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            content.findViewById<View>(R.id.diagnostics_scroll).requestFocus()
            uiHandler.removeCallbacks(refreshDiagnostics)
            uiHandler.postDelayed(refreshDiagnostics, DIAGNOSTICS_INTERVAL_MS)
        }
        openDialog(dialog, 740)
    }

    private fun updateDiagnostics() {
        val text = diagnosticsText ?: return
        val snapshot = runCatching { receiver?.diagnostics() }.getOrElse {
            getString(R.string.diagnostics_failed, it.localizedMessage.orEmpty())
        } ?: listOf(
            getString(R.string.diagnostics_unavailable),
            "",
            getString(R.string.diagnostics_model, Build.MANUFACTURER, Build.MODEL),
            getString(R.string.diagnostics_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            getString(R.string.diagnostics_abi, Build.SUPPORTED_ABIS.joinToString(", ")),
        ).joinToString("\n")
        // Keep the scroll position and avoid redundant accessibility events while polling.
        if (text.text.toString() != snapshot) text.text = snapshot
    }

    private fun openDialog(dialog: AlertDialog, widthDp: Int) {
        activeDialog?.dismiss()
        val returnFocus = currentFocus
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                diagnosticsText = null
                uiHandler.removeCallbacks(refreshDiagnostics)
                if (!isFinishing && !isDestroyed && overlay.visibility == View.VISIBLE) {
                    if (returnFocus?.isShown == true) returnFocus.requestFocus()
                    else settingsButton.requestFocus()
                }
            }
        }
        dialog.show()
        val metrics = resources.displayMetrics
        val width = min((widthDp * metrics.density).toInt(), (metrics.widthPixels * 0.92f).toInt())
        dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.decorView?.post {
            if (!dialog.isShowing) return@post
            val maximumHeight = (metrics.heightPixels * 0.90f).toInt()
            if ((dialog.window?.decorView?.height ?: 0) > maximumHeight) {
                dialog.window?.setLayout(width, maximumHeight)
            }
        }
    }

    private fun hideKeyboard(input: View) {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun senderName(name: String) = name.ifBlank { getString(R.string.unknown_sender) }

    /** Preserve the sender's aspect ratio without resizing the decoder's producer buffer. */
    private fun applyVideoAspect(sourceWidth: Int, sourceHeight: Int) {
        val width = screenRoot.width
        val height = screenRoot.height
        if (width <= 0 || height <= 0) {
            screenRoot.post {
                if (!isFinishing && !isDestroyed) applyVideoAspect(sourceWidth, sourceHeight)
            }
            return
        }
        val rect = VideoLayout.fitInside(sourceWidth, sourceHeight, width, height) ?: return
        val current = surfaceView.layoutParams as? FrameLayout.LayoutParams
        if (current != null && current.width == rect.width && current.height == rect.height) return
        surfaceView.layoutParams = FrameLayout.LayoutParams(rect.width, rect.height, Gravity.CENTER)
    }

    private companion object {
        const val PLAYBACK_HINT_DURATION_MS = 5_000L
        const val CONNECTING_HINT_DELAY_MS = 15_000L
        const val DIAGNOSTICS_INTERVAL_MS = 1_000L
    }
}
