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
    val assetId: Long? = null,
)

/** GitHub release feed with credentials scoped to API requests. */
class ReleaseClient private constructor(private val repository: String, private val token: String?) {
    constructor() : this("ilyamordasov/airscreen", null)

    private val api = "https://api.github.com/repos/$repository"

    internal fun verifyPrivateAccess() {
        val connection = connect(URL(api), JSON, token)
        try {
            requireAccess(connection.responseCode)
            check(connection.responseCode == 200) { "GitHub вернул HTTP ${connection.responseCode}" }
            val repo = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            check(repo.getBoolean("private"))
        } finally {
            connection.disconnect()
        }
    }

    fun latest(): ReleaseInfo? {
        val connection = connect(URL("$api/releases/latest"), JSON, token)
        try {
            if (connection.responseCode == 404) {
                if (token != null) verifyPrivateAccess()
                return null
            }
            if (token != null) requireAccess(connection.responseCode)
            check(connection.responseCode == 200) { "GitHub вернул HTTP ${connection.responseCode}" }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            require(tag.startsWith("v")) { "Некорректный тег релиза" }
            val version = ReleaseVersion.parse(tag) ?: error("Некорректная версия релиза")
            val expectedName = "FreeCast-TV-$version.apk"
            val expectedUrl = "https://github.com/$repository/releases/download/$tag/$expectedName"
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
            val assetId = if (token == null) null else asset.getLong("id").also { id ->
                require(id > 0 && asset.getString("url") == "$api/releases/assets/$id")
            }
            return ReleaseInfo(version, url, size, digest.substringAfter(':').lowercase(), assetId)
        } finally {
            connection.disconnect()
        }
    }

    /** Download into a disposable file; callers validate APK metadata before installation. */
    fun download(release: ReleaseInfo, destination: File, onProgress: (Int) -> Unit) {
        val connection = openAsset(release)
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

    private fun openAsset(release: ReleaseInfo): HttpURLConnection {
        var url = URL(if (token == null) release.downloadUrl else "$api/releases/assets/${requireNotNull(release.assetId)}")
        var credential = token
        repeat(4) {
            val connection = connect(url, "application/octet-stream", credential)
            try {
                val code = connection.responseCode
                if (credential != null) requireAccess(code)
                if (code == 200) return connection
                check(code in setOf(301, 302, 303, 307, 308)) { "Не удалось скачать APK: HTTP $code" }
                url = assetRedirect(url, connection.getHeaderField("Location"))
                credential = null
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("Слишком много перенаправлений при скачивании APK")
    }

    private fun requireAccess(code: Int) {
        if (code == 401 || code == 404) throw UpdateAccess.Required()
    }

    private fun connect(url: URL, accept: String, token: String? = null): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "AirScreen-Updater")
            if (token != null) {
                check(url.protocol == "https" && url.host == "api.github.com")
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

    companion object {
        internal fun privateFeed(token: String) = ReleaseClient("ilyamordasov/airscreen-ota", token)

        internal fun assetRedirect(from: URL, location: String?): URL {
            val to = URL(from, requireNotNull(location))
            require(to.protocol == "https" && to.userInfo == null && to.port in setOf(-1, 443) &&
                to.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
                    "github-releases.githubusercontent.com"))
            return to
        }

        private const val JSON = "application/vnd.github+json"
        const val MAX_APK_BYTES = 350L * 1024 * 1024
    }
}
