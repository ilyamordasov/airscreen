package com.airtv.receiver.vpn

import org.json.JSONArray
import org.json.JSONObject

/** Builds the minimal sing-box configuration; no profile JSON is ever written to logs. */
object BoxConfig {
    fun create(profile: VlessProfile): String {
        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", profile.server)
            put("server_port", profile.port)
            put("uuid", profile.uuid)
            profile.flow?.let { put("flow", it) }
            if (profile.security != "none") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", profile.sni ?: profile.server)
                    (profile.fingerprint ?: if (profile.security == "reality") "chrome" else null)?.let {
                        put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
                    }
                    if (profile.security == "reality") {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", profile.publicKey)
                            put("short_id", profile.shortId.orEmpty())
                        })
                    }
                })
            }
            if (profile.transport == "ws") {
                put("transport", JSONObject().apply {
                    put("type", "ws")
                    put("path", profile.path ?: "/")
                    profile.host?.let { put("headers", JSONObject().put("Host", it)) }
                })
            }
        }
        return JSONObject().apply {
            put("log", JSONObject().put("level", "warn"))
            put("dns", JSONObject().apply {
                put("servers", JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", "bootstrap"))
                    .put(JSONObject().put("type", "https").put("tag", "remote")
                        .put("server", "1.1.1.1").put("detour", "proxy")))
                put("final", "remote")
            })
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().put("172.19.0.1/30"))
                put("auto_route", true)
                put("dns_mode", "hijack")
                put("mtu", 1400)
            }))
            put("outbounds", JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct")))
            put("route", JSONObject().apply {
                put("rules", JSONArray()
                    .put(JSONObject().put("ip_is_private", true).put("action", "route").put("outbound", "direct"))
                    .put(JSONObject().put("ip_cidr", JSONArray().put("224.0.0.0/4").put("ff00::/8")
                        .put("169.254.0.0/16")).put("action", "route").put("outbound", "direct")))
                put("final", "proxy")
                put("default_domain_resolver", "bootstrap")
            })
        }.toString()
    }
}
