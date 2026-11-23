package com.airtv.receiver.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airtv.receiver.R
import com.airtv.receiver.ui.MainActivity
import io.nekohasekai.libbox.*

/** Per-app tunnel. The profile never leaves private app storage or enters diagnostics. */
class FreeCastVpnService : VpnService() {
    internal enum class State(val label: String) {
        OFF("VPN выключен"),
        NO_PROFILE("Добавьте VLESS-подписку"),
        CONNECTING("Подключение…"),
        CONNECTED("VPN подключён"),
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
            override fun serviceStop() { stopSelf() }
        }
    }
    @Volatile private var starting = false
    @Volatile private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(7, notification())
        if (command != null || starting) return START_STICKY
        val profile = VpnProfileStore.load(this)
        if (profile == null) {
            connectionState = State.NO_PROFILE
            stopSelf()
            return START_NOT_STICKY
        }
        connectionState = State.CONNECTING
        starting = true
        Thread {
            try {
                BoxRuntime.initialize(this)
                val next = CommandServer(platform, platform)
                next.start()
                next.startOrReloadService(BoxConfig.create(profile), OverrideOptions().apply {
                    includePackage = StringList(listOf(packageName))
                })
                if (destroyed) {
                    next.closeService()
                    next.close()
                } else {
                    command = next
                    connectionState = State.CONNECTED
                }
            } catch (error: Exception) {
                Log.w(TAG, "VPN startup failed (${error.javaClass.simpleName})")
                connectionState = State.FAILED
                stopSelf()
            } finally {
                starting = false
            }
        }.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        destroyed = true
        runCatching { command?.closeService() }
        runCatching { command?.close() }
        command = null
        tun?.close()
        tun = null
        platform.closeMonitor()
        if (connectionState == State.CONNECTING || connectionState == State.CONNECTED) connectionState = State.OFF
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf() }

    private fun establishTunnel(options: TunOptions): Int {
        check(prepare(this) == null) { "VPN permission missing" }
        val builder = Builder().setSession("FreeCast TV").setMtu(options.mtu)
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
                NotificationChannel("freecast_vpn", "FreeCast VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        val openMain = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 0, openMain, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "freecast_vpn")
            .setSmallIcon(R.drawable.ic_freecast).setContentTitle("FreeCast VPN")
            .setContentText("Подключение VLESS активно").setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "com.airtv.receiver.vpn.STOP"
        private const val TAG = "FreeCastVpnService"
        internal val state: String get() = connectionState.label
        @Volatile internal var connectionState = State.OFF
            private set

        @MainThread
        internal fun connect(context: Context) {
            if (connectionState == State.CONNECTING || connectionState == State.CONNECTED) return
            connectionState = State.CONNECTING
            try {
                ContextCompat.startForegroundService(context, Intent(context, FreeCastVpnService::class.java))
            } catch (error: RuntimeException) {
                Log.w(TAG, "VPN start request failed (${error.javaClass.simpleName})")
                connectionState = State.FAILED
            }
        }
    }
}
