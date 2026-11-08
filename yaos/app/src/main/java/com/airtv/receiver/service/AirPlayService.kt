/* Adapted for FreeCast TV, 2026. See LICENSE and THIRD_PARTY_NOTICES.md. */
package com.airtv.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.airtv.receiver.R
import com.airtv.receiver.airplay.AirPlayReceiver
import com.airtv.receiver.airplay.ReceiverState
import com.airtv.receiver.settings.ReceiverPreferences
import com.airtv.receiver.settings.ReceiverSettings
import com.airtv.receiver.ui.MainActivity
import java.util.concurrent.Executors

/** Serializes native start/stop outside the UI thread and owns the multicast lock. */
class AirPlayService : Service() {
    inner class LocalBinder : Binder() {
        val receiver: AirPlayReceiver get() = this@AirPlayService.receiver
        fun startReceiver() { desiredRunning = true; execute { startReceiverNow() } }
        fun stopReceiver() { desiredRunning = false; execute { stopReceiverNow() } }
        fun restartReceiver() {
            desiredRunning = true
            execute { stopReceiverNow(); startReceiverNow() }
        }
        fun applySettings(settings: ReceiverSettings) {
            // Persist before queuing so the settings dialog immediately sees the saved name.
            ReceiverPreferences.save(applicationContext, settings)
            restartReceiver()
        }
    }

    private val binder = LocalBinder()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "freecast-server") }
    private lateinit var receiver: AirPlayReceiver
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var destroyed = false
    @Volatile private var desiredRunning = false
    private var foreground = false
    private var connectivity: ConnectivityManager? = null
    @Volatile private var currentNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = currentNetwork != network
            currentNetwork = network
            if (changed && desiredRunning) execute {
                stopReceiverNow()
                if (desiredRunning) startReceiverNow()
            }
        }
        override fun onLost(network: Network) {
            if (currentNetwork == network) {
                currentNetwork = null
                if (desiredRunning) execute {
                    stopReceiverNow()
                    if (desiredRunning) startReceiverNow()
                }
            }
        }
    }

    private val stateListener: (ReceiverState) -> Unit = { state ->
        if (foreground && !destroyed) {
            runCatching { notificationManager().notify(NOTIFICATION_ID, buildNotification(state)) }
                .onFailure { Log.w(TAG, "notification update failed", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        receiver = AirPlayReceiver(applicationContext)
        receiver.addListener(stateListener)
        connectivity = getSystemService(ConnectivityManager::class.java)
        currentNetwork = connectivity?.activeNetwork
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "network callback unavailable", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification(receiver.state))
            foreground = true
        }
        if (intent?.action == ACTION_STOP) {
            binder.stopReceiver()
        } else {
            binder.startReceiver()
        }
        // No boot receiver and no resurrection of a stopped app; opening it starts reception.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun execute(block: () -> Unit) {
        if (destroyed) return
        runCatching {
            worker.execute {
                if (!destroyed) runCatching(block).onFailure { Log.e(TAG, "receiver command failed", it) }
            }
        }.onFailure { Log.w(TAG, "receiver is shutting down", it) }
    }

    private fun startReceiverNow() {
        if (!desiredRunning || destroyed) return
        acquireMulticastLock()
        if (!receiver.start()) releaseMulticastLock()
    }

    private fun stopReceiverNow() {
        receiver.stop()
        releaseMulticastLock()
    }

    override fun onDestroy() {
        destroyed = true
        desiredRunning = false
        receiver.removeListener(stateListener)
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        worker.execute {
            receiver.setSurface(null)
            stopReceiverNow()
        }
        worker.shutdown()
        foreground = false
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock(TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock unavailable (Ethernet needs no Wi-Fi lock)", it) }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private fun buildNotification(state: ReceiverState): Notification {
        val text = when (state) {
            is ReceiverState.Streaming -> getString(R.string.status_streaming, state.clientName)
            is ReceiverState.Connecting -> getString(R.string.status_connecting, state.clientName)
            is ReceiverState.AudioOnly -> getString(R.string.status_audio_only, state.clientName)
            is ReceiverState.Advertising -> getString(R.string.status_ready, state.name)
            is ReceiverState.Failed -> state.reason
            ReceiverState.Stopped -> getString(R.string.status_stopped)
            ReceiverState.Starting -> getString(R.string.status_starting)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 2, Intent(this, AirPlayService::class.java).setAction(ACTION_STOP), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build())
            .setOngoing(true).build()
    }

    companion object {
        private const val TAG = "FreeCastService"
        private const val CHANNEL_ID = "freecast_receiver"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "ru.freecast.tv.STOP"
        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, AirPlayService::class.java)) }
    }
}
