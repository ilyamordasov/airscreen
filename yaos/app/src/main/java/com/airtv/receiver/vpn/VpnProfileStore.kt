package com.airtv.receiver.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only app-private storage; Android backups are disabled in the manifest. */
object VpnProfileStore {
    private const val PREFS = "freecast_vless"
    private const val PROFILE = "profile"
    private const val SUBSCRIPTION = "subscription"
    private const val PROXIES = "proxies"

    fun save(context: Context, profile: VlessProfile) {
        check(prefs(context).edit().putString(PROFILE, profile.toJson().toString()).commit()) {
            "Не удалось сохранить VPN-профиль"
        }
    }

    internal fun saveImport(context: Context, profile: VlessProfile, subscription: String?) {
        check(prefs(context).edit().putString(PROFILE, profile.toJson().toString())
            .putString(SUBSCRIPTION, subscription).remove(PROXIES).commit())
    }

    internal fun subscription(context: Context): String? = prefs(context).getString(SUBSCRIPTION, null)

    internal fun saveProxies(context: Context, ranked: List<CheckedProxy>) {
        val list = JSONArray(ranked.map { result ->
            JSONObject().put("profile", result.profile.toJson()).put("latency", result.latencyMillis)
        })
        val editor = prefs(context).edit().putString(PROXIES, list.toString())
        ranked.firstOrNull()?.let { editor.putString(PROFILE, it.profile.toJson().toString()) }
        check(editor.commit())
    }

    internal fun proxies(context: Context): List<CheckedProxy> = runCatching {
        val raw = prefs(context).getString(PROXIES, null) ?: return emptyList()
        val list = JSONArray(raw)
        (0 until list.length()).map { index ->
            val value = list.getJSONObject(index)
            CheckedProxy(value.getJSONObject("profile").toProfile(), value.getLong("latency"))
        }
    }.getOrDefault(emptyList())

    private fun VlessProfile.toJson() = JSONObject().apply {
        put("server", server)
        put("port", port)
        put("uuid", uuid)
        put("name", name)
        put("security", security)
        put("sni", sni)
        put("fingerprint", fingerprint)
        put("publicKey", publicKey)
        put("shortId", shortId)
        put("flow", flow)
        put("transport", transport)
        put("path", path)
        put("host", host)
    }

    fun load(context: Context): VlessProfile? = runCatching {
        val raw = prefs(context).getString(PROFILE, null) ?: return null
        JSONObject(raw).toProfile()
    }.getOrNull()

    private fun JSONObject.toProfile() = VlessProfile(
        getString("server"), getInt("port"), getString("uuid"), getString("name"),
        getString("security"), optStringOrNull("sni"), optStringOrNull("fingerprint"),
        optStringOrNull("publicKey"), optStringOrNull("shortId"), optStringOrNull("flow"),
        getString("transport"), optStringOrNull("path"), optStringOrNull("host"),
    )

    fun clear(context: Context) {
        prefs(context).edit().remove(PROFILE).remove(SUBSCRIPTION).remove(PROXIES).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
