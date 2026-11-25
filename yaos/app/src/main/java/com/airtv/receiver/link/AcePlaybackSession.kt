package com.airtv.receiver.link

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.Serializable
import java.net.URI

internal class AcePlaybackSession private constructor(
    val manifest: String,
    val port: Int,
    private val statsPath: String?,
    private val commandPath: String?,
) : Serializable {
    data class Stats(val downloadKilobytesPerSecond: Long?, val peers: Long?)

    class PremiumRequired : IOException()

    fun stats(): Stats? = statsPath?.let {
        parseStats(JSONObject(read(port, it, 1_500)))
    }

    fun stop() {
        val path = commandPath ?: return
        read(port, "$path${if ('?' in path) '&' else '?'}method=stop", 2_000)
    }

    fun stopInBackground() {
        Thread {
            runCatching { stop() }.onFailure { Log.d("AcePlaybackSession", "Stop failed (${it.javaClass.simpleName})") }
        }.start()
    }

    companion object {
        fun open(port: Int, source: AceSource, timeout: Int = 40_000): AcePlaybackSession {
            val reply = JSONObject(read(port, "/ace/manifest.m3u8?format=json&${source.parameter}", timeout))
            val error = reply.opt("error")?.takeUnless { it == JSONObject.NULL }
            if (error != null) {
                if (error.toString().contains("premium", ignoreCase = true)) throw PremiumRequired()
                throw IOException("Engine rejected the source")
            }
            val playback = reply.getJSONObject("response")
            val manifest = localPath(playback.getString("playback_url"), port)
            val stats = playback.optString("stat_url").takeIf { it.isNotBlank() }?.let { localPath(it, port) }
            val command = playback.optString("command_url").takeIf { it.isNotBlank() }?.let { localPath(it, port) }
            return AcePlaybackSession("http://127.0.0.1:$port$manifest", port, stats, command)
        }

        internal fun localPath(value: String, port: Int): String {
            val uri = URI(value)
            if (uri.scheme != "http" || uri.host !in setOf("127.0.0.1", "localhost", "[::1]") ||
                uri.port != port || uri.rawUserInfo != null || uri.rawFragment != null ||
                uri.rawPath?.startsWith("/ace/") != true || uri.normalize().rawPath != uri.rawPath
            ) {
                throw IOException("Unexpected Ace Stream session URL")
            }
            return uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }

        internal fun parseStats(reply: JSONObject): Stats? {
            val result = reply.optJSONObject("response") ?: return null
            return Stats(
                result.optLong("speed_down", -1).takeIf { it >= 0 },
                result.optLong("peers", -1).takeIf { it >= 0 },
            )
        }

        private fun read(port: Int, path: String, timeout: Int): String =
            AceCatalogue.read(AceStreamEngine.connection(port, path).apply {
                connectTimeout = 2_000
                readTimeout = timeout
            })
    }
}
