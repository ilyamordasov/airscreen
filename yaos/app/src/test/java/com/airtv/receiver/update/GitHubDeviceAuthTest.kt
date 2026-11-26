package com.airtv.receiver.update

import java.io.IOException
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDeviceAuthTest {
    private val clientId = "ClientIdForTests"
    private val device = "d".repeat(40)
    private val access = "ghu_${"a".repeat(36)}"
    private val refresh = "ghr_${"b".repeat(36)}"

    @Test fun `challenge accepts the GitHub verification page and rejects another site`() {
        val auth = GitHubDeviceAuth(clientId) { path, params ->
            assertEquals("/login/device/code", path)
            assertEquals(mapOf("client_id" to clientId), params)
            challenge(GitHubDeviceAuth.VERIFY_URL)
        }
        assertEquals("ABCD-1234", auth.start().userCode)
        assertEquals(5, auth.start().interval)
        val spoofed = GitHubDeviceAuth(clientId) { _, _ -> challenge("https://example.org/login/device") }
        assertThrows(IOException::class.java) { spoofed.start() }
    }

    @Test fun `poll distinguishes pending rate limiting and denied access`() {
        val request = GitHubDeviceAuth.Challenge(device, "ABCD-1234", 900, 5)
        val states = mapOf(
            "authorization_pending" to GitHubDeviceAuth.State.PENDING,
            "slow_down" to GitHubDeviceAuth.State.SLOW_DOWN,
            "expired_token" to GitHubDeviceAuth.State.EXPIRED,
            "access_denied" to GitHubDeviceAuth.State.DENIED,
        )
        for ((error, expected) in states) {
            val auth = GitHubDeviceAuth(clientId) { path, params ->
                assertEquals("/login/oauth/access_token", path)
                assertEquals(device, params["device_code"])
                assertFalse(params.containsKey("client_secret"))
                JSONObject().put("error", error)
            }
            assertEquals(expected, auth.poll(request).state)
        }
    }

    @Test fun `device token refresh rotates credentials without a client secret`() {
        val auth = GitHubDeviceAuth(clientId) { _, params ->
            assertEquals("refresh_token", params["grant_type"])
            assertEquals(refresh, params["refresh_token"])
            assertFalse(params.containsKey("client_secret"))
            JSONObject().put("access_token", access).put("refresh_token", refresh + "c")
                .put("token_type", "bearer").put("expires_in", 28_800)
        }
        val credentials = auth.refresh(refresh)
        assertEquals(access, credentials.access)
        assertEquals(refresh + "c", credentials.refresh)
        assertTrue(credentials.expiresAt > System.currentTimeMillis())
        val revoked = GitHubDeviceAuth(clientId) { _, _ -> JSONObject().put("error", "bad_refresh_token") }
        assertThrows(UpdateAccess.Required::class.java) { revoked.refresh(refresh) }
    }

    @Test fun `asset redirects only allow GitHub HTTPS download hosts`() {
        val api = URL("https://api.github.com/repos/owner/app/releases/assets/123")
        assertEquals("release-assets.githubusercontent.com",
            ReleaseClient.assetRedirect(api, "https://release-assets.githubusercontent.com/data").host)
        for (target in listOf("https://example.org/data", "http://release-assets.githubusercontent.com/data",
            "https://release-assets.githubusercontent.com:1443/data", "https://user@objects.githubusercontent.com/data")) {
            assertThrows(IllegalArgumentException::class.java) { ReleaseClient.assetRedirect(api, target) }
        }
    }

    private fun challenge(uri: String) = JSONObject().put("device_code", device).put("user_code", "ABCD-1234")
        .put("expires_in", 900).put("interval", 5).put("verification_uri", uri)
}
