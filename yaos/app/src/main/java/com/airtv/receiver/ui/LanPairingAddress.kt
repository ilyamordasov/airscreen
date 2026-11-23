package com.airtv.receiver.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal object LanPairingAddress {
    @Suppress("DEPRECATION")
    fun find(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return (listOfNotNull(manager.activeNetwork) + manager.allNetworks).asSequence()
            .distinct()
            .filter { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }
            .flatMap { network -> manager.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence() }
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    }
}
