package com.airtv.receiver.vpn

import android.content.Context
import android.util.Log
import com.airtv.receiver.link.TvCrateResolver
import com.airtv.receiver.link.TvCrateResponse
import com.airtv.receiver.link.TvCrateTransport
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CheckedProxy(val profile: VlessProfile, val latencyMillis: Long)

/** A short-lived, authenticated loopback proxy routes each check through its own VLESS outbound. */
internal class ProxyScanner(private val context: Context) {
    fun scan(profiles: List<VlessProfile>, progress: (Int, Int) -> Unit): List<CheckedProxy> {
        TvCrateResolver.ensureActive()
        BoxRuntime.initialize(context)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val password = UUID.randomUUID().toString()
        val supported = profiles.filter { profile ->
            TvCrateResolver.ensureActive()
            runCatching { Libbox.checkConfig(BoxConfig.probe(listOf(profile), port, password)) }.isSuccess
        }
        if (supported.isEmpty()) return emptyList()
        val platform = AndroidBoxPlatform(context)
        val command = CommandServer(platform, platform)
        val workers = Executors.newFixedThreadPool(8)
        try {
            // The probe engine needs neither the command socket nor an Android VPN tunnel.
            command.startOrReloadService(BoxConfig.probe(supported, port, password), OverrideOptions())
            val checked = ExecutorCompletionService<CheckedProxy?>(workers)
            supported.forEachIndexed { index, profile ->
                checked.submit { check(profile, port, "probe-$index", password) }
            }
            val passed = mutableListOf<CheckedProxy>()
            repeat(supported.size) { index ->
                checked.take().get()?.let(passed::add)
                progress(index + 1, supported.size)
            }
            return passed.sortedBy { it.latencyMillis }
        } finally {
            workers.shutdownNow()
            runCatching { command.closeService() }
            runCatching { command.close() }
            runCatching { platform.closeMonitor() }
        }
    }

    private fun check(profile: VlessProfile, port: Int, user: String, password: String): CheckedProxy? {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(user, password)).build()
            }
            .connectionPool(ConnectionPool(1, 15, TimeUnit.SECONDS))
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        return try {
            CheckedProxy(profile, checkTvCrate(ProbeTransport(client)))
        } catch (error: Exception) {
            Log.d(TAG, "Proxy check rejected: " + error.javaClass.simpleName)
            null
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    private class ProbeTransport(private val client: OkHttpClient) : TvCrateTransport {
        override fun get(url: String, headers: Map<String, String>): TvCrateResponse {
            var uri = URI(url)
            repeat(4) {
                TvCrateResolver.ensureActive()
                val host = uri.host?.lowercase().orEmpty()
                require(uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
                    (uri.port == -1 || uri.port == 443) && (host == "tvcrate.com" || host.endsWith(".tvcrate.com")))
                val request = Request.Builder().url(uri.toString()).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                }.build()
                client.newCall(request).execute().use { response ->
                    if (response.code in REDIRECTS) {
                        uri = uri.resolve(response.header("Location") ?: throw IOException("Missing redirect"))
                        return@repeat
                    }
                    if (!response.isSuccessful) return TvCrateResponse(response.code, "")
                    val source = response.body?.source() ?: throw IOException("Missing response body")
                    val buffer = Buffer()
                    val limit = TvCrateResolver.MAX_RESPONSE_BYTES.toLong()
                    while (buffer.size <= limit) {
                        TvCrateResolver.ensureActive()
                        if (source.read(buffer, minOf(8192L, limit + 1 - buffer.size)) == -1L) break
                    }
                    require(buffer.size <= limit) { "Response exceeds limit" }
                    return TvCrateResponse(response.code, buffer.readUtf8())
                }
            }
            throw IOException("Too many redirects")
        }
    }

    companion object {
        private const val TAG = "ProxyScanner"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
