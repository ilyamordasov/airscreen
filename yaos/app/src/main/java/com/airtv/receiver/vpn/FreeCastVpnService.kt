package com.airtv.receiver.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.airtv.receiver.R
import com.airtv.receiver.ui.MainActivity
import io.nekohasekai.libbox.*
import java.net.NetworkInterface as JavaNetworkInterface

/** Per-app tunnel. The profile never leaves private app storage or enters diagnostics. */
class FreeCastVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    internal enum class State(val label: String) {
        OFF("VPN выключен"),
        NO_PROFILE("Добавьте VLESS-подписку"),
        CONNECTING("Подключение…"),
        CONNECTED("VPN подключён"),
        FAILED("Не удалось подключить VPN"),
    }

    private var command: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkListener: ConnectivityManager.NetworkCallback? = null
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
                val setup = SetupOptions().apply {
                    basePath = filesDir.path
                    workingPath = filesDir.path
                    tempPath = cacheDir.path
                    logMaxLines = 100
                }
                synchronized(FreeCastVpnService::class.java) {
                    if (!initialized) {
                        Libbox.setup(setup)
                        initialized = true
                    }
                }
                val next = CommandServer(this, this)
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
        networkListener?.let { connectivity.unregisterNetworkCallback(it) }
        networkListener = null
        if (connectionState == State.CONNECTING || connectionState == State.CONNECTED) connectionState = State.OFF
        super.onDestroy()
    }

    override fun onRevoke() { stopSelf() }

    override fun openTun(options: TunOptions): Int {
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

    override fun autoDetectInterfaceControl(fd: Int) {
        check(protect(fd)) { "Unable to route socket outside the VPN" }
    }
    override fun usePlatformAutoDetectInterfaceControl() = true
    override fun includeAllNetworks() = false
    override fun underNetworkExtension() = false
    override fun usePlatformBridge() = false
    override fun usePlatformShell() = false
    override fun useProcFS() = false
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun clearDNSCache() = Unit
    override fun checkPlatformShell() = Unit
    override fun createBridge(options: BridgeOptions): BridgeSession = error("Bridge unavailable")
    override fun lookupSFTPServer() = ""
    override fun lookupUser(username: String): PlatformUser = error("User lookup unavailable")
    override fun openShellSession(user: PlatformUser, command: String, environ: StringIterator, term: String, rows: Int, cols: Int): ShellSession = error("Shell unavailable")
    override fun readSystemSSHHostKey() = ""
    override fun readWIFIState(): WIFIState? = null
    override fun registerMyInterface(name: String) = Unit
    override fun tailscaleHostname() = ""
    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner = error("Process lookup unavailable")

    override fun getInterfaces(): NetworkInterfaceIterator {
        val links = connectivity.allNetworks.mapNotNull(connectivity::getLinkProperties)
            .associateBy { it.interfaceName }
        val interfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { item ->
            io.nekohasekai.libbox.NetworkInterface().apply {
                name = item.name
                index = item.index
                mtu = item.mtu
                if (links.containsKey(item.name) && item.isUp) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                dnsServer = StringList(links[item.name]?.dnsServers.orEmpty().mapNotNull { it.hostAddress })
                addresses = StringList(item.interfaceAddresses.mapNotNull { address ->
                    val host = address.address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    "$host/${address.networkPrefixLength}"
                })
            }
        }.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = interfaces.hasNext()
            override fun next() = interfaces.next()
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        fun update(network: Network?, properties: LinkProperties?) {
            val name = properties?.interfaceName.orEmpty()
            val index = runCatching { JavaNetworkInterface.getByName(name)?.index }.getOrNull() ?: -1
            val metered = network?.let { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false } ?: false
            listener.updateDefaultInterface(name, index, metered, false)
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val active = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current = active

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                current = network
                update(network, properties)
            }

            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                update(null, null)
            }
        }
        update(active, active?.let(connectivity::getLinkProperties))
        when {
            Build.VERSION.SDK_INT >= 31 -> connectivity.registerBestMatchingNetworkCallback(
                request, callback, Handler(Looper.getMainLooper()),
            )
            Build.VERSION.SDK_INT >= 28 -> connectivity.requestNetwork(request, callback)
            else -> connectivity.registerDefaultNetworkCallback(callback)
        }
        networkListener = callback
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkListener?.let { connectivity.unregisterNetworkCallback(it) }
        networkListener = null
    }

    override fun serviceReload() = Unit
    override fun serviceStop() { stopSelf() }
    override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) = Unit
    override fun connectSSHAgent() = -1

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
        @Volatile private var initialized = false

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

private class StringList(values: List<String>) : StringIterator {
    private val iterator = values.iterator()
    private val size = values.size
    override fun hasNext() = iterator.hasNext()
    override fun next() = iterator.next()
    override fun len() = size
}
