package com.airtv.receiver.vpn

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
}
