package com.airtv.receiver.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

internal class GitHubDeviceAuth(
    private val clientId: String,
    private val post: (String, Map<String, String>) -> JSONObject = ::postToGitHub,
) {
    class Challenge(val deviceCode: String, val userCode: String, val expiresIn: Int, val interval: Int)
    class Credential(val access: String, val refresh: String, val expiresAt: Long)

    enum class State { PENDING, SLOW_DOWN, EXPIRED, DENIED }
    data class Poll(val credential: Credential? = null, val state: State? = null)

    fun start(): Challenge {
        val reply = post("/login/device/code", mapOf("client_id" to clientId))
        val userCode = reply.optString("user_code")
        val deviceCode = reply.optString("device_code")
        val expiresIn = reply.optInt("expires_in")
        val interval = reply.optInt("interval")
        if (!userCode.matches(Regex("[A-Z0-9]{4}-[A-Z0-9]{4}")) ||
            !deviceCode.matches(Regex("[a-zA-Z0-9]{20,128}")) ||
            reply.optString("verification_uri") != VERIFY_URL || expiresIn !in 1..1800 || interval !in 1..60
        ) throw IOException("Invalid GitHub device challenge")
        return Challenge(deviceCode, userCode, expiresIn, interval)
    }

    fun poll(challenge: Challenge): Poll {
        val reply = token("urn:ietf:params:oauth:grant-type:device_code", "device_code" to challenge.deviceCode)
        val state = when (reply.optString("error")) {
            "" -> return Poll(credential(reply))
            "authorization_pending" -> State.PENDING
            "slow_down" -> State.SLOW_DOWN
            "expired_token" -> State.EXPIRED
            "access_denied" -> State.DENIED
            else -> throw IOException("GitHub device authorization failed")
        }
        return Poll(state = state)
    }

    fun refresh(refresh: String): Credential {
        val reply = token("refresh_token", "refresh_token" to refresh)
        when (reply.optString("error")) {
            "" -> return credential(reply)
            "bad_refresh_token", "expired_token", "invalid_grant" -> throw UpdateAccess.Required()
            else -> throw IOException("GitHub could not refresh authorization")
        }
    }

    private fun token(grant: String, value: Pair<String, String>): JSONObject =
        post("/login/oauth/access_token", mapOf("client_id" to clientId, "grant_type" to grant, value))

    private fun credential(reply: JSONObject): Credential {
        val access = reply.optString("access_token")
        val refresh = reply.optString("refresh_token")
        val expiresIn = reply.optLong("expires_in", 0)
        if (!access.startsWith("ghu_") || !access.matches(Regex("[a-zA-Z0-9_]{20,255}")) ||
            !reply.optString("token_type").equals("bearer", ignoreCase = true) ||
            expiresIn !in 0L..31_536_000L ||
            (expiresIn > 0 && !refresh.matches(Regex("[a-zA-Z0-9_]{20,255}")))
        ) throw IOException("Invalid GitHub App token")
        val expiresAt = if (expiresIn == 0L) Long.MAX_VALUE else System.currentTimeMillis() + expiresIn * 1000
        return Credential(access, refresh, expiresAt)
    }

    companion object {
        const val VERIFY_URL = "https://github.com/login/device"

        private fun postToGitHub(path: String, parameters: Map<String, String>): JSONObject {
            val body = parameters.entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
            }.toByteArray(Charsets.UTF_8)
            val connection = (URL("https://github.com$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val input = if (code == 200) connection.inputStream else connection.errorStream
                if (code !in setOf(200, 400, 401) || input == null) throw IOException("GitHub HTTP $code")
                return input.bufferedReader().use { JSONObject(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
