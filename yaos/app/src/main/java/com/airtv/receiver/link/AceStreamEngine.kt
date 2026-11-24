package com.airtv.receiver.link

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.airtv.receiver.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/** Keeps local engine ownership separate from the external Ace Stream app. */
internal object AceStreamEngine {
    private const val HOST = "127.0.0.1"
    private const val EXTERNAL_PORT = 6878
    private const val TAG = "EmbeddedAceStream"
    private val preparation = Any()
    private val lifecycle = Any()
    private var generation = 0L
    private var running: Engine? = null

    private data class Engine(val process: java.lang.Process, val port: Int)

    fun port(context: Context): Int {
        if (!BuildConfig.EMBEDDED_ACE) return EXTERNAL_PORT
        val request = synchronized(lifecycle) { generation }
        val current = start(context.applicationContext, request)
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isRunning(current.process)) throw IOException("Embedded Ace Stream exited during startup")
            if (ready(current.port)) return current.port
            Thread.sleep(250)
        }
        synchronized(lifecycle) {
            if (running === current) {
                running = null
                current.process.destroy()
            }
        }
        throw IOException("Embedded Ace Stream startup timed out")
    }

    fun connection(port: Int, path: String): HttpURLConnection =
        (URL("http://$HOST:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }

    fun stop() {
        if (!BuildConfig.EMBEDDED_ACE) return
        synchronized(lifecycle) {
            generation++
            running?.process?.destroy()
            running = null
        }
    }

    private fun start(context: Context, request: Long): Engine = synchronized(preparation) {
        synchronized(lifecycle) {
            if (request != generation) throw IOException("Embedded Ace Stream was stopped")
            running?.takeIf { isRunning(it.process) }?.let { return it }
        }
        val runtime = prepare(context)
        val home = File(context.filesDir, "ace-home").apply { mkdirs() }
        val cache = File(context.cacheDir, "ace-data").apply { mkdirs() }
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val deviceId = File(home, "device-id").let { file ->
            if (file.isFile) file.readText() else UUID.randomUUID().toString().also(file::writeText)
        }
        val address = InetAddress.getByName(HOST)
        val (httpPort, apiPort) = ServerSocket(0, 1, address).use { http ->
            ServerSocket(0, 1, address).use { api -> http.localPort to api.localPort }
        }
        val builder = ProcessBuilder(
            File(nativeDir, "libacepython.so").path, "-B", File(runtime, "ace_bootstrap.py").path,
            "--http-port", httpPort.toString(), "--api-port", apiPort.toString(),
            "--access-token", UUID.randomUUID().toString(), "--state-dir", home.path,
            "--cache-dir", cache.path, "--cache-max-bytes", "268435456",
            "--log-max-size", "1048576", "--log-backup-count", "1",
            "--log-file", File(home, "core.log").path,
        ).directory(runtime).redirectErrorStream(true)
        builder.environment().apply {
            put("PYTHONHOME", File(runtime, "python").path)
            put("PYTHONPATH", listOf("python/lib/stdlib.zip", "python/lib/modules", "lib").joinToString(":") {
                File(runtime, it).path
            })
            put("LD_LIBRARY_PATH", "${nativeDir.path}:${File(runtime, "python/lib").path}")
            put("HOME", home.path)
            put("TEMP", cache.path)
            put("ACESTREAM_HOME", home.path)
            put("ACE_PARENT_PID", Process.myPid().toString())
            put("ACE_HTTP_PORT", httpPort.toString())
            put("ACE_API_PORT", apiPort.toString())
            put("ACE_ANDROID_INFO", androidInfo(context, runtime, home, deviceId).toString())
        }
        synchronized(lifecycle) {
            if (generation != request) throw IOException("Embedded Ace Stream was stopped")
            val process = builder.start()
            Thread({ drainStartup(process, File(home, "startup.log")) }, "ace-startup-output").start()
            Engine(process, httpPort).also { running = it }
        }
    }

    private fun androidInfo(context: Context, runtime: File, home: File, deviceId: String): JSONObject {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val network = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val packages = context.packageManager
        val browser = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        return JSONObject().apply {
            put("getAceStreamHome", home.path)
            put("getAppDir", runtime.path)
            put("getAppId", context.packageName)
            put("getAppVersionName", BuildConfig.VERSION_NAME)
            put("getAppVersionCode", BuildConfig.VERSION_CODE)
            put("getEngineVersionCode", 3018000)
            put("getApiLevel", Build.VERSION.SDK_INT)
            put("getDeviceId", deviceId)
            put("getDeviceName", Build.DEVICE)
            put("getDeviceProductName", Build.PRODUCT)
            put("getDeviceModel", Build.MODEL)
            put("getDeviceManufacturer", Build.MANUFACTURER)
            put("getArch", "arm64-v8a")
            put("getDeviceABI", "arm64-v8a")
            put("getRAMSize", memory.totalMem)
            put("getMaxMemory", Runtime.getRuntime().maxMemory())
            put("getTotalMemory", Runtime.getRuntime().totalMemory())
            put("getMemoryClass", activity.memoryClass)
            put("getDisplayLanguage", Locale.getDefault().displayLanguage)
            put("getLocale", Locale.getDefault().language)
            put("isAndroidTv", packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            put("isMobileNetwork", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            put("hasBrowser", browser.resolveActivity(packages) != null)
            put("hasWebView", packages.hasSystemFeature(PackageManager.FEATURE_WEBVIEW))
        }
    }

    private fun prepare(context: Context): File {
        val runtime = File(context.filesDir, "ace-runtime-3.1.80")
        val marker = File(runtime, ".ready")
        if (!marker.isFile) {
            extract(context, runtime)
            check(marker.createNewFile())
        }
        context.assets.open("ace_bootstrap.py").use { input ->
            File(runtime, "ace_bootstrap.py").outputStream().use { input.copyTo(it) }
        }
        return runtime
    }

    private fun extract(context: Context, runtime: File) {
        runtime.deleteRecursively()
        check(runtime.mkdirs()) { "Could not prepare Ace Stream files" }
        val prefix = runtime.canonicalPath + File.separator
        for (archive in listOf("arm64-v8a_private_py.zip", "arm64-v8a_private_res.zip")) {
            ZipInputStream(context.assets.open("ace-runtime/$archive").buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(runtime, entry.name)
                    check(target.canonicalPath.startsWith(prefix)) { "Invalid Ace Stream archive path" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { zip.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun ready(port: Int): Boolean = runCatching {
        val request = connection(port, "/webui/api/service?method=get_version").apply {
            connectTimeout = 400
            readTimeout = 500
        }
        try {
            val body = request.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONObject("result")?.optString("version")?.isNotBlank() == true
        } finally {
            request.disconnect()
        }
    }.getOrDefault(false)

    private fun isRunning(process: java.lang.Process): Boolean = runCatching { process.exitValue() }.isFailure

    private fun drainStartup(process: java.lang.Process, logs: File) {
        runCatching {
            process.inputStream.use { input ->
                logs.outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    var remaining = 128 * 1024
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val write = minOf(count, remaining)
                        output.write(buffer, 0, write)
                        remaining -= write
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Failed to read engine startup output") }
    }
}
