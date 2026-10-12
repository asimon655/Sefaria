package org.asimon.sefaria.data

import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.ref.nextSection
import org.asimon.sefaria.core.ref.prevSection
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.CalendarDto
import org.asimon.sefaria.data.remote.ErrorDto
import org.asimon.sefaria.data.remote.ManifestDto
import org.asimon.sefaria.data.remote.ResolveDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes JSON recorded from the real server (src/test/resources/fixtures) and checks the app agrees with it. */
class ContractTest {
    private val bookNames = listOf("Genesis", "Berakhot", "Pesach_Haggadah", "Rashi_on_Genesis")
    private val catalog = Catalog(bookNames.map { Book(SefariaJson.decodeFromString<BookDto>(fixture("book_$it.json"))) })

    @Test
    fun decodesEveryEndpoint() {
        assertTrue(SefariaJson.decodeFromString<ManifestDto>(fixture("manifest.json")).buildId.isNotEmpty())
        val toc = SefariaJson.decodeFromString<TocDto>(fixture("toc.json"))
        assertEquals("Tanakh", toc.tree.first().en)
        assertEquals("ref_not_found", SefariaJson.decodeFromString<ErrorDto>(fixture("error_ref_not_found.json")).error.code)
        val r = SefariaJson.decodeFromString<ResolveDto>(fixture("resolve_Ber_2a_5.json"))
        assertEquals("Berakhot.2a.5" to "segment", r.ref to r.kind)
        val cal = SefariaJson.decodeFromString<CalendarDto>(fixture("calendar_2026-12-05.json"))
        val parasha = cal.items.first()
        assertEquals("parashat_hashavua", parasha.id)
        assertEquals("Vayeshev" to "וישב", parasha.display.en to parasha.display.he)
        assertEquals("Genesis", parasha.book)
        assertEquals(8, parasha.aliyot.size)
        assertEquals("שבת חנוכה - יום א", parasha.note?.he)
        assertEquals("haftarah", cal.items[1].id)
        // every item opens: its ref parses as one of the catalog's books where we have them
        val inGenesis = cal.items.filter { it.book == "Genesis" }
        assertTrue(inGenesis.isNotEmpty())
        for (it in inGenesis) assertEquals(it.ref, Ref.parse(catalog, it.ref).url())

        val gen = catalog.bookByTitle("Genesis")!!
        assertEquals(1533, gen.slots)
        assertTrue(gen.dto.versions.any { it.lang == "he" && it.default })
        assertEquals("Bereshit", gen.dto.altStructs["Parasha"]!!.first().en)
        assertEquals("Commentary", catalog.bookByTitle("Rashi on Genesis")!!.dto.dependence)
    }

    /** The app computes prev/next from the schema; they must match what the server returns. */
    @Test
    fun navigationMatchesServer() {
        for (name in listOf("Genesis.1", "Genesis.50", "Berakhot.2a", "Pesach_Haggadah,_Kadesh", "Rashi_on_Genesis.1.1")) {
            val t = SefariaJson.decodeFromString<TextDto>(fixture("text_$name.json"))
            val section = Ref.parse(catalog, t.ref)
            assertEquals(name, t.sections.single().ref, section.url())
            assertEquals("$name next", t.next, section.book.nextSection(section)?.url())
            assertEquals("$name prev", t.prev, section.book.prevSection(section)?.url())
            assertEquals("$name he_ref", t.heRef, section.hebrew())
            // the section has as many segments as the schema says
            assertEquals(name, section.slots().count(), t.sections.single().let { maxOf(it.he.size, it.en.size) })
        }
    }

    @Test
    fun realRefs() {
        assertEquals(29 until 34, Ref.parse(catalog, "Genesis 1:30-2:3").slots())
        assertEquals("Berakhot.2a.5", Ref.parse(catalog, "ברכות ב ע\"א:ה").url())
        assertEquals("Pesach_Haggadah,_Magid,_Four_Questions", Ref.parse(catalog, "Pesach Haggadah, Magid, Four Questions").url())
        assertEquals("Rashi_on_Genesis.1.1.2", Ref.parse(catalog, "Rashi on Genesis 1:1:2").url())
    }
}
