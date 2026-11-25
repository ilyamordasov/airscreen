package com.airtv.receiver.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AcePlaybackSessionTest {
    @Test fun `loopback aliases keep the original encoded session path`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            assertEquals(
                "/ace/m/source.m3u8?token=a%2Bb%2Fc",
                AcePlaybackSession.localPath("http://$host:6878/ace/m/source.m3u8?token=a%2Bb%2Fc", 6878),
            )
        }
    }

    @Test fun `session rejects URLs outside the active engine`() {
        for (url in listOf(
            "http://localhost:9999/ace/m/source.m3u8",
            "http://example.com:6878/ace/m/source.m3u8",
            "http://username@localhost:6878/ace/m/source.m3u8",
            "http://localhost:6878/ace/../source.m3u8",
        )) {
            assertThrows(IOException::class.java) { AcePlaybackSession.localPath(url, 6878) }
        }
    }

    @Test fun `session stats distinguish zero speed from missing data`() {
        assertEquals(
            AcePlaybackSession.Stats(257, 4),
            AcePlaybackSession.parseStats(JSONObject("""{"response":{"speed_down":257,"peers":4}}""")),
        )
        assertEquals(
            AcePlaybackSession.Stats(0, null),
            AcePlaybackSession.parseStats(JSONObject("""{"response":{"speed_down":0}}""")),
        )
        assertEquals(
            AcePlaybackSession.Stats(null, 0),
            AcePlaybackSession.parseStats(JSONObject("""{"response":{"peers":0}}""")),
        )
    }
}
