package com.airtv.receiver.vpn

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class VlessProfileTest {
    private val link = "vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=reality&sni=cdn.example.com&pbk=publicKey&sid=1234&type=tcp#Test"

    @Test fun parsesPlainAndBase64Subscription() {
        val expected = VlessProfile.parse(link)
        assertEquals("example.com", expected.server)
        assertEquals("publicKey", expected.publicKey)
        assertEquals(expected, VlessProfile.fromSubscription("# one\n$link\n"))
        assertEquals(expected, VlessProfile.fromSubscription(Base64.getEncoder().encodeToString(link.toByteArray(StandardCharsets.UTF_8))))
        assertEquals(expected, VlessProfile.fromSubscription(Base64.getUrlEncoder().withoutPadding().encodeToString(link.toByteArray(StandardCharsets.UTF_8))))
        assertFalse(expected.toString().contains(expected.uuid))
    }

    @Test fun rejectsUnsupportedAndMissingValues() {
        assertThrows(IllegalArgumentException::class.java) { VlessProfile.parse(link.replace("type=tcp", "type=grpc")) }
        assertThrows(IllegalArgumentException::class.java) { VlessProfile.parse(link.replace("pbk=publicKey", "pbk=")) }
        assertThrows(IllegalArgumentException::class.java) { VlessProfile.fromSubscription("not a subscription") }
    }

    @Test fun parsesXraySubscriptionAndSkipsUnsupportedOutbounds() {
        val config = """
            {
              "remarks": "Test",
              "outbounds": [
                {"protocol": "freedom"},
                {
                  "protocol": "vless",
                  "streamSettings": {"network": "xhttp", "security": "tls"},
                  "settings": {"vnext": [{"address": "unsupported.example", "port": 443,
                    "users": [{"id": "11111111-1111-4111-8111-111111111111"}]}]}
                },
                {
                  "protocol": "vless",
                  "streamSettings": {"network": "tcp", "security": "reality", "realitySettings": {
                    "serverName": "cdn.example.com", "publicKey": "publicKey", "shortId": "1234"
                  }},
                  "settings": {"vnext": [{"address": "example.com", "port": 443,
                    "users": [{"id": "11111111-1111-4111-8111-111111111111", "encryption": "none"}]}]}
                }
              ]
            }
        """.trimIndent()
        val expected = VlessProfile.parse(link)
        assertEquals(expected, VlessProfile.fromSubscription(config))
        assertEquals(expected, VlessProfile.fromSubscription("[{},$config]"))
    }

    @Test fun enablesSocketProtectionForTheTunnel() {
        val config = JSONObject(BoxConfig.create(VlessProfile.parse(link)))
        assertTrue(config.getJSONObject("route").optBoolean("auto_detect_interface"))
    }

    @Test fun usesSupportedFingerprintForStoredFirefoxAlias() {
        val fingerprints = mapOf(
            "hellofirefox_120" to "firefox",
            "HelloFirefox_120" to "firefox",
            "edge" to "edge",
        )
        for ((stored, expected) in fingerprints) {
            val profile = VlessProfile.parse(link).copy(fingerprint = stored)
            val config = JSONObject(BoxConfig.create(profile))
            val tls = config.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
            assertEquals(expected, tls.getJSONObject("utls").getString("fingerprint"))
            assertTrue(tls.getJSONObject("reality").getBoolean("enabled"))
        }
    }
}
