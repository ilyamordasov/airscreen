package com.airtv.receiver.link

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class TvCrateResolverTest {
    private val key = "3b7ddc43-eaa0-444a-a895-6dd081dfffb6"
    private val channel = TvCrateResolver.MATCH_PREMIER_URL
    private val player = "https://tvcrate.com/player/?key=$key"
    private val api = "https://live.tvcrate.com/gettoken.php?key=$key&n=1"
    private val now = 1_795_350_000L
    private val media = "https://edge1.tvcrate.com/stream_14/index.m3u8?token=TEST_TOKEN&expires=1795435200"

    private data class Request(val url: String, val headers: Map<String, String>)

    private class FakeTransport(private val responses: Map<String, TvCrateResponse>) : TvCrateTransport {
        val requests = mutableListOf<Request>()
        override fun get(url: String, headers: Map<String, String>): TvCrateResponse {
            requests += Request(url, headers)
            return responses[url] ?: throw AssertionError("Unexpected request: $url")
        }
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/tvcrate/$name")).readText()

    private fun transport(
        channelHtml: String = fixture("channel.html"),
        playerHtml: String = fixture("player.html"),
        token: TvCrateResponse = TvCrateResponse(200, """{"url":"$media"}"""),
        apiUrl: String = api,
    ) = FakeTransport(mapOf(
        channel to TvCrateResponse(200, channelHtml),
        player to TvCrateResponse(200, playerHtml),
        apiUrl to token,
    ))

    private fun resolver(http: TvCrateTransport) = TvCrateResolver(http) { now }

    private fun expectIo(block: () -> Unit): IOException {
        try { block() } catch (error: IOException) { return error }
        throw AssertionError("Expected IOException")
    }

    @Test fun `resolves the observed channel iframe and native HLS API`() {
        val http = transport()
        val result = resolver(http).resolve(channel)
        assertEquals(listOf(channel, player, api), http.requests.map { it.url })
        assertEquals(media, result.mediaUrl)
        assertEquals(channel, result.sourcePage)
        assertEquals(1_795_435_200L, result.expiresAtEpochSeconds)
        assertEquals("https://tvcrate.com", result.requestHeaders["Origin"])
        assertEquals(player, result.requestHeaders["Referer"])
        assertEquals(TvCrateResolver.USER_AGENT, result.requestHeaders["User-Agent"])
        assertEquals(result.requestHeaders["User-Agent"], http.requests.last().headers["User-Agent"])
        assertEquals(result.requestHeaders["Origin"], http.requests.last().headers["Origin"])
        assertEquals(result.requestHeaders["Referer"], http.requests.last().headers["Referer"])
    }

    @Test fun `accepts the observed JSON fixture with signed native URL`() {
        val http = transport(token = TvCrateResponse(200, fixture("native-token.json")))
        assertEquals(media, resolver(http).resolve(channel).mediaUrl)
    }

    @Test fun `direct embed performs only player and token requests`() {
        val http = transport()
        assertEquals(player, resolver(http).resolve(player).sourcePage)
        assertEquals(listOf(player, api), http.requests.map { it.url })
    }

    @Test fun `recognizes valid page links and rejects spoofed or ambiguous inputs`() {
        listOf(channel, "$channel?t=abc#video", player, "https://www.tvcrate.com/channel/match-premer-501/")
            .forEach { assertTrue(it, TvCrateResolver.supports(it)) }
        listOf(
            "http://tvcrate.com/channel/match-premer-501",
            "https://tvcrate.com.evil.example/channel/match-premer-501",
            "https://tvcrate.com@evil.example/player/?key=$key",
            "https://tvcrate.com:8443/channel/match-premer-501",
            "https://live.tvcrate.com/channel/match-premer-501",
            "https://tvcrate.com/football/example",
            "https://tvcrate.com/%63hannel/match-premer-501",
            "https://tvcrate.com/player/?key=bad",
            "$player&key=$key",
            "javascript:alert(1)",
        ).forEach { assertFalse(it, TvCrateResolver.supports(it)) }
    }

    @Test fun `unsupported inputs make no network requests`() {
        val http = FakeTransport(emptyMap())
        expectIo { resolver(http).resolve("https://example.com/watch") }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `relative src single quotes and HTML entities are decoded`() {
        val url = TvCrateResolver.playerFromHtml(
            "<IFRAME title='test' src='/player/?key=$key&amp;embed=1'></IFRAME>", channel)
        assertEquals("$player&embed=1", url)
    }

    @Test fun `protocol relative and unquoted iframe src are supported`() {
        assertEquals(player, TvCrateResolver.playerFromHtml("<iframe src=//tvcrate.com/player/?key=$key>", channel))
    }

    @Test fun `ignores unrelated frames comments and script templates`() {
        val html = """
            <!-- <iframe src="https://tvcrate.com/player/?key=00000000-0000-0000-0000-000000000000"> -->
            <script>const template = '<iframe src="https://tvcrate.com/player/?key=11111111-1111-1111-1111-111111111111">';</script>
            <iframe src="https://ads.example/player/?key=$key"></iframe>
            <iframe src="$player"></iframe>
        """.trimIndent()
        assertEquals(player, TvCrateResolver.playerFromHtml(html, channel))
    }

    @Test fun `multiple different channel frames are not silently selected`() {
        val other = player.replace(key, "11111111-1111-1111-1111-111111111111")
        assertNull(TvCrateResolver.playerFromHtml("<iframe src='$player'><iframe src='$other'>", channel))
        assertEquals(player, TvCrateResolver.playerFromHtml("<iframe src='$player'><iframe src='$player'>", channel))
    }

    @Test fun `missing iframe fails without calling token API`() {
        val http = transport(channelHtml = "<h1>Unavailable</h1>")
        assertTrue(expectIo { resolver(http).resolve(channel) }.message!!.contains("Плеер"))
        assertEquals(listOf(channel), http.requests.map { it.url })
    }

    @Test fun `does not execute computed Javascript configuration`() {
        val html = "<script>const STREAM_KEY = atob('anything'); const PIN_SRC = ''; </script>"
        val http = transport(playerHtml = html)
        expectIo { resolver(http).resolve(channel) }
        assertEquals(2, http.requests.size)
    }

    @Test fun `different stream key fails instead of playing a different channel`() {
        val html = fixture("player.html").replace(key, "11111111-1111-1111-1111-111111111111")
        val http = transport(playerHtml = html)
        expectIo { resolver(http).resolve(channel) }
        assertEquals(2, http.requests.size)
    }

    @Test fun `pinned HLS URL uses native player without token request`() {
        val html = fixture("player.html").replace("const PIN_SRC = \"\";", "const PIN_SRC = \"https:\\/\\/cdn.example.org\\/live.m3u8\";")
        val http = transport(playerHtml = html)
        val result = resolver(http).resolve(channel)
        assertEquals("https://cdn.example.org/live.m3u8", result.mediaUrl)
        assertNull(result.expiresAtEpochSeconds)
        assertEquals(2, http.requests.size)
    }

    @Test fun `resolve mode takes precedence and fresh retry uses provider refresh option`() {
        val html = fixture("player.html")
            .replace("const RESOLVE_MODE = false;", "const RESOLVE_MODE = true;")
            .replace("const PIN_SRC = \"\";", "const PIN_SRC = \"https://wrong.example/live.m3u8\";")
        val http = transport(playerHtml = html, apiUrl = "$api&fresh=1")
        assertEquals(media, resolver(http).resolve(channel, fresh = true).mediaUrl)
        assertEquals("no-cache", http.requests.last().headers["Cache-Control"])
    }

    @Test fun `every play obtains fresh token instead of caching media URLs`() {
        val http = transport()
        resolver(http).apply { resolve(channel); resolve(channel, fresh = true) }
        assertEquals(2, http.requests.count { it.url == api })
    }

    @Test fun `HTTP errors remain visible and do not try other sources`() {
        for (code in listOf(403, 404, 429, 503)) {
            val http = transport(token = TvCrateResponse(code, ""))
            assertTrue(expectIo { resolver(http).resolve(channel) }.message!!.contains(code.toString()))
            assertEquals(3, http.requests.size)
        }
    }

    @Test fun `malformed or missing token payloads fail explicitly`() {
        for (json in listOf("<html>Denied</html>", "{}", "{\"url\":null}", "{\"url\":false}", "{\"url\":\"\"}")) {
            expectIo { resolver(transport(token = TvCrateResponse(200, json))).resolve(channel) }
        }
    }

    @Test fun `rejects insecure non HLS and credential-bearing media URLs`() {
        for (url in listOf("http://edge1.tvcrate.com/live.m3u8", "https://example.org/page", "https://example.org/clip.mp4",
            "https://user:password@example.org/live.m3u8", "file:///live.m3u8", "https://example.org/live.m3u8#fragment")) {
            val http = transport(token = TvCrateResponse(200, """{"url":"$url"}"""))
            expectIo { resolver(http).resolve(channel) }
        }
    }

    @Test fun `signed query is not decoded or reordered`() {
        val url = "https://edge1.tvcrate.com/stream_14/index.m3u8?token=A%2FB%2B%3D&expires=1795435200&x=a+b"
        val http = transport(token = TvCrateResponse(200, """{"url":"$url"}"""))
        assertEquals(url, resolver(http).resolve(channel).mediaUrl)
    }

    @Test fun `expired and invalid expiry are rejected`() {
        for (expiry in listOf((now - 1).toString(), now.toString(), "0", "invalid")) {
            val url = media.replace("1795435200", expiry)
            expectIo { resolver(transport(token = TvCrateResponse(200, """{"url":"$url"}"""))).resolve(channel) }
        }
    }

    @Test fun `oversized responses are rejected`() {
        val http = transport(channelHtml = "a".repeat(TvCrateResolver.MAX_RESPONSE_BYTES + 1))
        expectIo { resolver(http).resolve(channel) }
        assertEquals(1, http.requests.size)
    }

    @Test fun `cancelled worker does not begin any requests`() {
        val http = transport()
        try {
            Thread.currentThread().interrupt()
            assertTrue(expectIo { resolver(http).resolve(channel) } is InterruptedIOException)
            assertTrue(http.requests.isEmpty())
        } finally { Thread.interrupted() }
    }

    @Test fun `cancellation during a response prevents subsequent requests`() {
        var count = 0
        val http = TvCrateTransport { _, _ ->
            count++
            Thread.currentThread().interrupt()
            TvCrateResponse(200, fixture("channel.html"))
        }
        try {
            assertTrue(expectIo { resolver(http).resolve(channel) } is InterruptedIOException)
            assertEquals(1, count)
        } finally { Thread.interrupted() }
    }

    @Test fun `HTTP client rejects foreign origins before connecting`() {
        for (url in listOf("http://tvcrate.com/", "https://example.com/", "https://tvcrate.com:8443/", "https://user@tvcrate.com/")) {
            expectIo { TvCrateHttpTransport().get(url, emptyMap()) }
        }
    }
}
