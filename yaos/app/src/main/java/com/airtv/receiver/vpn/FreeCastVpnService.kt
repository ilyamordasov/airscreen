package com.airtv.receiver.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airtv.receiver.FreeCastApplication
import com.airtv.receiver.R
import com.airtv.receiver.ui.MainActivity
import io.nekohasekai.libbox.*
import java.util.concurrent.Executors

/** Per-app tunnel. The profile never leaves private app storage or enters diagnostics. */
class FreeCastVpnService : VpnService() {
    internal enum class State(val label: String) {
        OFF("VPN выключен"),
        NO_PROFILE("Добавьте VLESS-подписку"),
        CONNECTING("Подключение…"),
        CONNECTED("VPN подключён"),
        STOPPING("Отключение…"),
        FAILED("Не удалось подключить VPN"),
    }

    private var command: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null
    private val platform by lazy {
        object : AndroidBoxPlatform(this) {
            override fun openTun(options: TunOptions): Int = establishTunnel(options)
            override fun autoDetectInterfaceControl(fd: Int) {
                check(protect(fd)) { "Unable to route socket outside the VPN" }
            }
            override fun serviceStop() {
                main.post {
                    if (activeService === this@FreeCastVpnService && !stopping) stopTunnel(State.FAILED)
                }
            }
        }
    }
    private var started = false
    @Volatile private var stopping = false
    @Volatile private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(7, notification())
        val app = application as FreeCastApplication
        if (started || stopping) return START_NOT_STICKY
        if (intent?.getLongExtra(EXTRA_REQUEST, -1) != connectionRequest ||
            app.foregroundSession == null || connectionState != State.CONNECTING) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        started = true
        activeService = this
        startCore()
        return START_NOT_STICKY
    }

    private fun startCore() {
        val profile = VpnProfileStore.load(this)
        if (profile == null) {
            stopTunnel(State.NO_PROFILE)
            return
        }
        connectionState = State.CONNECTING
        worker.execute {
            if (destroyed || stopping) return@execute
            try {
                BoxRuntime.initialize(this)
                val next = CommandServer(platform, platform)
                command = next
                next.start()
                next.startOrReloadService(BoxConfig.create(profile), OverrideOptions().apply {
                    includePackage = StringList(listOf(packageName))
                })
                main.post { if (!destroyed && !stopping) connectionState = State.CONNECTED }
            } catch (error: Exception) {
                Log.w(TAG, "VPN startup failed (${error.javaClass.simpleName})")
                main.post {
                    if (!destroyed && !stopping) {
                        stopTunnel(State.FAILED)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (activeService === this) stopTunnel()
        super.onDestroy()
    }

    // Android binds to an established VpnService until its TUN is closed.
    // Stopping the started service alone leaves that binding and the VPN active.
    private fun stopTunnel(finalState: State = State.OFF) {
        if (!started || stopping) return
        stopping = true
        connectionState = State.STOPPING
        worker.execute {
            val oldCore = command
            command = null
            // libbox holds a duplicate TUN descriptor. Close its service as well as the Android descriptor.
            runCatching { oldCore?.closeService() }
                .onFailure { Log.w(TAG, "VPN core stop failed (${it.javaClass.simpleName})") }
            runCatching { tun?.close() }
            tun = null
            runCatching { oldCore?.close() }
            runCatching { platform.closeMonitor() }
            main.post {
                if (activeService !== this) return@post
                val requestedSession = pendingConnection
                pendingConnection = null
                val app = application as FreeCastApplication
                val restart = app.hasVisibleActivities && requestedSession != null &&
                    app.foregroundSession === requestedSession && prepare(this) == null
                stopping = false
                if (restart && !destroyed) {
                    startCore()
                } else {
                    started = false
                    activeService = null
                    connectionState = if (app.foregroundSession == null) State.OFF else finalState
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!destroyed) stopSelf()
                    if (restart) requestConnection(app)
                }
            }
        }
    }

    override fun onRevoke() {
        main.post {
            if (activeService !== this) return@post
            pendingConnection = null
            stopTunnel()
        }
    }

    private fun establishTunnel(options: TunOptions): Int {
        check(!destroyed && !stopping) { "VPN service stopped" }
        check(prepare(this) == null) { "VPN permission missing" }
        val builder = Builder().setSession(getString(R.string.app_name)).setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val addresses = options.inet4Address
        while (addresses.hasNext()) addresses.next().let { builder.addAddress(it.address(), it.prefix()) }
        val addresses6 = options.inet6Address
        while (addresses6.hasNext()) addresses6.next().let { builder.addAddress(it.address(), it.prefix()) }
        builder.addRoute("0.0.0.0", 0)
        if (options.inet6Address.hasNext()) builder.addRoute("::", 0)
        val dns = options.dnsServerAddress
        while (dns.hasNext()) builder.addDnsServer(dns.next())
        builder.addAllowedApplication(packageName)
        val descriptor = builder.establish() ?: error("VPN interface unavailable")
        tun = descriptor
        return descriptor.fd
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("freecast_vpn", getString(R.string.vpn_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val openMain = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, openMain, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "freecast_vpn")
            .setSmallIcon(R.drawable.ic_freecast).setContentTitle(getString(R.string.vpn_name))
            .setContentText("Подключение VLESS активно").setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        private const val EXTRA_REQUEST = "connectionRequest"
        private const val TAG = "FreeCastVpnService"
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "freecast-vpn") }
        private var activeService: FreeCastVpnService? = null
        private var pendingConnection: Any? = null
        private var connectionRequest = 0L
        internal val state: String get() = connectionState.label
        @Volatile internal var connectionState = State.OFF
            private set

        @MainThread
        internal fun connect(context: Context) {
            val app = context.applicationContext as FreeCastApplication
            if (!app.hasVisibleActivities) return
            if (connectionState == State.STOPPING) {
                pendingConnection = app.foregroundSession
                return
            }
            if (connectionState == State.CONNECTING || connectionState == State.CONNECTED) return
            requestConnection(context)
        }

        private fun requestConnection(context: Context) {
            connectionState = State.CONNECTING
            try {
                val request = ++connectionRequest
                ContextCompat.startForegroundService(context, Intent(context, FreeCastVpnService::class.java)
                    .putExtra(EXTRA_REQUEST, request))
            } catch (error: RuntimeException) {
                Log.w(TAG, "VPN start request failed (${error.javaClass.simpleName})")
                connectionState = State.FAILED
            }
        }

        @MainThread
        internal fun restart(context: Context) {
            val app = context.applicationContext as FreeCastApplication
            val session = app.foregroundSession ?: return
            if (!app.hasVisibleActivities) return
            val service = activeService
            if (service == null) {
                connect(context)
            } else {
                pendingConnection = session
                service.stopTunnel()
            }
        }

        @MainThread
        internal fun disconnect(context: Context) {
            pendingConnection = null
            val service = activeService
            if (service == null) {
                connectionRequest++
                context.stopService(Intent(context, FreeCastVpnService::class.java))
                connectionState = State.OFF
            } else {
                service.stopTunnel()
            }
        }
    }
}
