package com.airtv.receiver.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverSettingsTest {
    @Test fun `invalid persisted profile falls back to bounded 1080p30`() {
        val display = ReceiverSettings("TV", Int.MAX_VALUE, -1).display()
        assertEquals(1920, display.width)
        assertEquals(1080, display.height)
        assertEquals(30, display.fps)
    }

    @Test fun `720p60 remains landscape with the selected frame rate`() {
        val display = ReceiverSettings("TV", 720, 60).display()
        assertEquals(1280, display.width)
        assertEquals(720, display.height)
        assertEquals(60, display.fps)
    }
}
