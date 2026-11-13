package com.airtv.receiver.vpn

import android.content.Context
import org.json.JSONObject

/** Only app-private storage; Android backups are disabled in the manifest. */
object VpnProfileStore {
    private const val PREFS = "freecast_vless"
    private const val PROFILE = "profile"

    fun save(context: Context, profile: VlessProfile) {
        val json = JSONObject().apply {
            put("server", profile.server)
            put("port", profile.port)
            put("uuid", profile.uuid)
            put("name", profile.name)
            put("security", profile.security)
            put("sni", profile.sni)
            put("fingerprint", profile.fingerprint)
            put("publicKey", profile.publicKey)
            put("shortId", profile.shortId)
            put("flow", profile.flow)
            put("transport", profile.transport)
            put("path", profile.path)
            put("host", profile.host)
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PROFILE, json.toString()).commit()) {
            "Не удалось сохранить VPN-профиль"
        }
    }

    fun load(context: Context): VlessProfile? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROFILE, null) ?: return null
        val value = JSONObject(raw)
        VlessProfile(
            value.getString("server"), value.getInt("port"), value.getString("uuid"), value.getString("name"),
            value.getString("security"), value.optStringOrNull("sni"), value.optStringOrNull("fingerprint"),
            value.optStringOrNull("publicKey"), value.optStringOrNull("shortId"), value.optStringOrNull("flow"),
            value.getString("transport"), value.optStringOrNull("path"), value.optStringOrNull("host"),
        )
    }.getOrNull()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PROFILE).apply()
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
