package com.airtv.receiver.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import io.nekohasekai.libbox.*
import java.net.NetworkInterface as JavaNetworkInterface

internal open class AndroidBoxPlatform(context: Context) : PlatformInterface, CommandServerHandler {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var networkListener: ConnectivityManager.NetworkCallback? = null

    override fun openTun(options: TunOptions): Int = error("TUN unavailable")
    override fun autoDetectInterfaceControl(fd: Int) = Unit
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
        closeMonitor()
    }

    fun closeMonitor() {
        networkListener?.let { connectivity.unregisterNetworkCallback(it) }
        networkListener = null
    }

    override fun serviceReload() = Unit
    override fun serviceStop() = Unit
    override fun getSystemProxyStatus() = SystemProxyStatus().apply { available = false; enabled = false }
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) = Unit
    override fun connectSSHAgent() = -1

}

internal object BoxRuntime {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        Libbox.setup(SetupOptions().apply {
            basePath = context.filesDir.path
            workingPath = context.filesDir.path
            tempPath = context.cacheDir.path
            logMaxLines = 100
        })
        initialized = true
    }
}

internal class StringList(values: List<String>) : StringIterator {
    private val iterator = values.iterator()
    private val size = values.size
    override fun hasNext() = iterator.hasNext()
    override fun next() = iterator.next()
    override fun len() = size
}
