package org.asimon.sefaria.feature.reader

import androidx.compose.ui.text.AnnotatedString
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.RichText
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderSelectionTest {
    private val catalog = Catalog(SefariaJson.decodeFromString<List<BookDto>>(fixture("fixture_catalog.json")).map(::Book))

    private fun seg(ref: String, he: String?, en: String?): SegmentItem {
        val r = Ref.parse(catalog, ref)
        return SegmentItem(
            r.url(), r, r.number().toString(), he?.let { RichText(AnnotatedString(it)) }, en?.let { RichText(AnnotatedString(it)) },
            r.section().human(), highlighted = false,
        )
    }

    private val a = seg("Genesis 1:1", "בראשית", "In the beginning")
    private val b = seg("Genesis 1:2", "והארץ", "Now the earth")
    private val items: List<ReaderItem> = listOf(HeaderItem("h:Genesis.1", "Genesis 1"), a, b)

    @Test
    fun keysRunFromAnchorToEndInEitherOrder() {
        val sel = ReaderSelection()
        assertEquals(emptySet<String>(), sel.keys(items))
        sel.start(b.key)
        assertEquals(setOf(b.key), sel.keys(items))
        sel.extendTo(a.key)
        assertEquals(setOf(a.key, b.key), sel.keys(items))
        sel.clear()
        assertEquals(emptySet<String>(), sel.keys(items))
    }

    @Test
    fun refIsASegmentOrARange() {
        assertNull(selectionRef(emptyList()))
        assertEquals("Genesis 1:1", selectionRef(listOf(a))!!.human())
        assertEquals("Genesis 1:1-2", selectionRef(listOf(a, b))!!.human())
        assertEquals("בראשית", selectionPreview(listOf(a, b)))
    }

    @Test
    fun citationFollowsTheLayout() {
        assertEquals("בראשית\nוהארץ\n— Genesis 1:1-2", selectionCitation(listOf(a, b), Layout.Hebrew, hebrew = false))
        assertEquals("In the beginning\n— Genesis 1:1", selectionCitation(listOf(a), Layout.English, hebrew = false))
        assertEquals("בראשית\nIn the beginning\n— ${Ref.parse(catalog, "Genesis 1:1").hebrew()}", selectionCitation(listOf(a), Layout.Stacked, hebrew = true))
        assertEquals("", selectionCitation(emptyList(), Layout.Hebrew, hebrew = false))
    }
}
