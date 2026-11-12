package com.airtv.receiver.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class ReleaseInfo(
    val version: ReleaseVersion,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
)

/** Public GitHub release feed; never requires credentials on the television. */
class ReleaseClient {
    fun latest(): ReleaseInfo? {
        val connection = connect(URL("$API/releases/latest"))
        try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "GitHub вернул HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            require(tag.startsWith("v")) { "Некорректный тег релиза" }
            val version = ReleaseVersion.parse(tag) ?: error("Некорректная версия релиза")
            val expectedName = "FreeCast-TV-$version.apk"
            val expectedUrl = "$RELEASES/download/$tag/$expectedName"
            val assets = json.getJSONArray("assets")
            val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == expectedName }
            require(matches.size == 1) { "APK релиза не найден или неоднозначен" }
            val asset = matches.single()
            val url = asset.getString("browser_download_url")
            require(url == expectedUrl) { "Адрес APK не совпадает с релизом" }
            val size = asset.getLong("size")
            require(size in 1..MAX_APK_BYTES) { "Некорректный размер APK" }
            val digest = asset.getString("digest")
            require(digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) { "Нет SHA-256 APK" }
            return ReleaseInfo(version, url, size, digest.substringAfter(':').lowercase())
        } finally {
            connection.disconnect()
        }
    }

    /** Download into a disposable file; callers validate APK metadata before installation. */
    fun download(release: ReleaseInfo, destination: File, onProgress: (Int) -> Unit) {
        val connection = connect(URL(release.downloadUrl))
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            check(connection.responseCode == 200 && connection.url.protocol == "https") {
                "Не удалось скачать APK: HTTP ${connection.responseCode}"
            }
            var total = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_BYTES && total <= release.size) { "Размер APK превышает заявленный" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress((total * 100 / release.size).toInt())
                    }
                }
            }
            require(total == release.size) { "APK загружен не полностью" }
            val actual = digest.digest().joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            require(actual == release.sha256) { "Контрольная сумма APK не совпала" }
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FreeCast-TV-Updater")
        }

    companion object {
        private const val API = "https://api.github.com/repos/ilyamordasov/airscreen"
        private const val RELEASES = "https://github.com/ilyamordasov/airscreen/releases"
        const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}
