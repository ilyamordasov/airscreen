package com.airtv.receiver.link

import org.junit.Assert.*
import org.junit.Test

class ProgrammeGuideTest {
    @Test fun `TVCrate timings include programmes continuing past midnight`() {
        val guide = ProgrammeGuide.tvCrate("""
            <script type="application/ld+json">[{"@type":"ItemList","itemListElement":[
            {"item":{"@type":"BroadcastEvent","name":"Live match","startDate":"2026-11-23T23:30:00+03:00","endDate":"2026-11-24T01:00:00+03:00"}},
            {"item":{"@type":"BroadcastEvent","name":"Review","startDate":"2026-11-24T01:00:00+03:00","endDate":"2026-11-24T01:20:00+03:00"}}]}]</script>
        """.trimIndent())
        val now = requireNotNull(ProgrammeGuide.moscowTime("2026-11-24", "00:40"))
        val window = ProgrammeGuide.window(guide, now)
        assertEquals("Live match", window.current?.title)
        assertEquals("Review", window.next?.title)
        assertEquals(20, window.current?.remainingMinutes(now))
        assertEquals("Review", ProgrammeGuide.window(guide, now + 20 * 60_000).current?.title)
    }

    @Test fun `Okko entries use calendar date and the following programme as the end`() {
        val guide = ProgrammeGuide.iptvX("""
            <div class="epg-day" data-d="2026-11-25"><ul class="epg-list">
              <li><span class="t">01:00</span><details><summary>Review 2</summary></details></li></ul></div>
            <div class="epg-day" data-d="2026-11-24"><ul class="epg-list">
              <li><span class="t">22:45</span><details><summary>Club A &amp; Club B</summary><p>Summary</p></details></li>
              <li><span class="t">99:99</span><details><summary>Invalid programme</summary></details></li></ul></div>
        """.trimIndent())
        val now = requireNotNull(ProgrammeGuide.moscowTime("2026-11-25", "00:59")) + 18_000
        val window = ProgrammeGuide.window(guide, now)
        assertEquals(2, guide.size)
        assertEquals("Club A & Club B", window.current?.title)
        assertEquals(1, window.current?.remainingMinutes(now))
        assertEquals("Review 2", window.next?.title)
        assertNull(ProgrammeGuide.window(guide, now + 43_000).current?.remainingMinutes(now + 43_000))
    }

    @Test fun `an explicit gap does not extend an ended programme`() {
        val first = ProgrammeGuide.Entry("Ended", 0, 60_000)
        val next = ProgrammeGuide.Entry("Next", 120_000, 180_000)
        assertEquals(ProgrammeGuide.Window(null, next), ProgrammeGuide.window(listOf(first, next), 75_000))
        assertEquals(ProgrammeGuide.Window(null, null), ProgrammeGuide.window(listOf(first, next), 180_000))
    }
}
