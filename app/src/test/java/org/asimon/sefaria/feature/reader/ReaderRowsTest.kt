package org.asimon.sefaria.feature.reader

import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRowsTest {
    private val catalog = Catalog(SefariaJson.decodeFromString<List<BookDto>>(fixture("fixture_catalog.json")).map(::Book))

    private fun seg(ref: String): SegmentItem {
        val r = Ref.parse(catalog, ref)
        return SegmentItem(r.url(), r, r.number().toString(), null, null, r.section().human(), highlighted = false)
    }

    // Genesis 1:30-31, then chapter 2:1-2, each section headed
    private val items: List<ReaderItem> = listOf(
        HeaderItem("h:Genesis.1", "Genesis 1"), seg("Genesis 1:30"), seg("Genesis 1:31"),
        HeaderItem("h:Genesis.2", "Genesis 2"), seg("Genesis 2:1"), seg("Genesis 2:2"),
    )

    @Test
    fun linesKeepOneRowPerItem() {
        val r = ReaderRows.build(items, paragraphs = false)
        assertEquals(6, r.rows.size)
        assertEquals(4, r.rowOf("Genesis.2.1"))
        assertEquals(4, r.firstItemOf(4))
    }

    @Test
    fun paragraphsGroupEachSection() {
        val r = ReaderRows.build(items, paragraphs = true)
        assertEquals(4, r.rows.size) // header, paragraph, header, paragraph
        val p = r.rows[3] as ParagraphRow
        assertEquals("p:Genesis.2.1", p.key) // keyed by its first segment: stable when sections load around it
        assertEquals(listOf("Genesis.2.1", "Genesis.2.2"), p.segments.map { it.key })
        assertEquals(3, r.rowOf("Genesis.2.2"))
        assertEquals(4, r.firstItemOf(3))
        assertEquals(5, r.lastItemOf(3))
        assertTrue(r.rows[0] is HeaderRow)
    }

    @Test
    fun rangeLabels() {
        assertEquals("Genesis 1:30-31", rangeLabel(seg("Genesis 1:30"), seg("Genesis 1:31"), hebrew = false))
        assertEquals("Genesis 1:31-2:2", rangeLabel(seg("Genesis 1:31"), seg("Genesis 2:2"), hebrew = false))
        assertEquals("בראשית א:ל-לא", rangeLabel(seg("Genesis 1:30"), seg("Genesis 1:31"), hebrew = true))
        assertEquals("Genesis 2:1", rangeLabel(seg("Genesis 2:1"), seg("Genesis 2:1"), hebrew = false))
    }
}
