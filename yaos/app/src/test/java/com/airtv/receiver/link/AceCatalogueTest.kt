package com.airtv.receiver.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceCatalogueTest {
    @Test fun `only directory links become entries and the latest vote determines priority`() {
        val broken = "a".repeat(40)
        val working = "b".repeat(40)
        val unknown = "c".repeat(40)
        val entries = AceCatalogue.directory("""
            <p>ContentID ${"d".repeat(40)}</p><ul id="acestreamids">
            ${row(broken, "2026-11-22 10:00:00", "2026-11-23 10:00:00")}
            ${row(unknown)}
            ${row(working, "2026-11-25 10:00:00", "2026-11-20 10:00:00")}
            ${row(working)}
            </ul><a href="acestream://${"e".repeat(40)}">Outside the directory</a>
        """.trimIndent())
        assertEquals(listOf(working, unknown, broken), entries.map { it.source.id })
        assertEquals(listOf(AceCatalogue.Report.WORKING, AceCatalogue.Report.UNKNOWN, AceCatalogue.Report.BROKEN),
            entries.map { it.report })
        assertEquals("content_id=$working", entries.first().source.parameter)
    }

    @Test fun `search entries play by their infohash directly`() {
        val hash = "A1".repeat(20)
        val data = JSONObject("""{"result":{"results":[{"name":"Channel","items":[{"infohash":"$hash"},{"infohash":"invalid"}]}]}}""")
        val source = AceCatalogue.search(data).single().source
        assertEquals("infohash=${hash.lowercase()}", source.parameter)
        assertNull(AceSource.parse(AceSource.Kind.CONTENT_ID, "$hash&format=redirect"))
    }

    private fun row(id: String, good: String = "", bad: String = "") = """
        <li class="acestreamidID"><div class="col_id">
          <a href="acestream://$id">Stream</a>
          <div class="vote"><i class="goodId"></i><time datetime="$good"></time></div>
          <div class="vote"><i class="broken"></i><time datetime="$bad"></time></div>
        </div></li>
    """.trimIndent()
}
