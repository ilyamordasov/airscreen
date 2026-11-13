package com.airtv.receiver.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaCandidateTest {
    @Test fun `accepts direct HLS and MP4 with signed query`() {
        assertEquals("https://cdn.example.org/live/master.m3u8?key=abc", MediaCandidate.parse("https://cdn.example.org/live/master.m3u8?key=abc"))
        assertEquals("https://cdn.example.org/clip.mp4", MediaCandidate.parse("https://cdn.example.org/clip.mp4"))
    }

    @Test fun `rejects pages local files and ephemeral blob URLs`() {
        listOf("https://example.org/watch", "blob:https://example.org/id", "http://example.org/live.m3u8", "file:///video.mp4")
            .forEach { assertNull(MediaCandidate.parse(it)) }
    }
}
