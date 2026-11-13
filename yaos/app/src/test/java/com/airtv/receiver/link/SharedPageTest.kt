package com.airtv.receiver.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPageTest {
    @Test fun `accepts an ordinary Safari page link`() {
        assertEquals(
            "https://yandex.ru/video/preview/12174246280771146129",
            SharedPage.parse("  https://yandex.ru/video/preview/12174246280771146129  "),
        )
    }

    @Test fun `keeps a provider query and fragment`() {
        val url = "https://example.org/watch?id=123&token=abc#player"
        assertEquals(url, SharedPage.parse(url))
    }

    @Test fun `rejects unsafe or malformed links`() {
        listOf(
            "", "http://example.org/video", "file:///sdcard/video.mp4",
            "javascript:alert(1)", "https://user:pass@example.org/video",
            "https:///missing-host", "https://example.org/\nother",
            "https://example.org/" + "x".repeat(2048),
        ).forEach { assertNull(SharedPage.parse(it)) }
    }
}
