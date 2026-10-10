package org.asimon.sefaria.core.ref

import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Runs the server's shared vectors (server/internal/ref/testdata) against the same fixture books. */
class RefTest {
    private val catalog = Catalog(
        SefariaJson.decodeFromString<List<BookDto>>(fixture("fixture_catalog.json")).map(::Book)
    )

    @Test
    fun vectors() {
        val rows = fixture("vectors.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }
        check(rows.size >= 20)
        for (row in rows) {
            val (input, url, human, he, start, end) = row.split('\t')
            val r = Ref.parse(catalog, input)
            assertEquals(input, url, r.url())
            assertEquals(input, human, r.human())
            assertEquals(input, he, r.hebrew())
            assertEquals(input, start.toInt() until end.toInt(), r.slots())
            for (form in listOf(r.url(), r.human(), r.hebrew())) {
                assertEquals("round trip $form", url, Ref.parse(catalog, form).url())
            }
        }
    }

    @Test
    fun errors() {
        for ((input, want) in listOf(
            "Genesiss 1:1" to RefError.UnknownTitle,
            "Genesis 1:1:1" to RefError.BadSection,
            "Genesis x" to RefError.BadSection,
        )) {
            try {
                Ref.parse(catalog, input)
                fail("$input parsed")
            } catch (e: RefException) {
                assertEquals(input, want, e.error)
            }
        }
        for (input in listOf("Genesis 4:1", "Genesis 1:40", "Berakhot 1a")) {
            try {
                Ref.parse(catalog, input).slots()
                fail("$input in range")
            } catch (e: RefException) {
                assertEquals(input, RefError.OutOfRange, e.error)
            }
        }
    }

    @Test
    fun atAndSection() {
        for ((book, slot, seg, section) in listOf(
            Quad("Genesis", 0, "Genesis.1.1", "Genesis.1"),
            Quad("Genesis", 31, "Genesis.2.1", "Genesis.2"),
            Quad("Berakhot", 10, "Berakhot.2b.1", "Berakhot.2b"),
            Quad("Pesach Haggadah", 4, "Pesach_Haggadah,_Magid,_Ha_Lachma_Anya.2", "Pesach_Haggadah,_Magid,_Ha_Lachma_Anya"),
            Quad("Ohr LaYesharim on Jerusalem Talmud Yoma", 10, "Ohr_LaYesharim_on_Jerusalem_Talmud_Yoma.1.2.3", "Ohr_LaYesharim_on_Jerusalem_Talmud_Yoma.1.2"),
        )) {
            val r = Ref.at(catalog.bookByTitle(book)!!, slot)
            assertEquals(seg, r.url())
            assertEquals(section, r.section().url())
        }
    }

    @Test
    fun navigation() {
        val gen = catalog.bookByTitle("Genesis")!!
        val g1 = Ref.parse(catalog, "Genesis 1")
        assertEquals("Genesis.2", gen.nextSection(g1)?.url())
        assertNull(gen.prevSection(g1))
        assertNull(gen.nextSection(Ref.parse(catalog, "Genesis 3")))
        assertEquals("Genesis.3", Ref.parse(catalog, "Genesis 3:15").openSection().url())
        assertEquals("Genesis.1", Ref.parse(catalog, "Genesis").openSection().url())

        // empty dafim are skipped; complex nodes are crossed
        val ber = catalog.bookByTitle("Berakhot")!!
        assertEquals("Berakhot.2a", Ref.parse(catalog, "Berakhot").openSection().url())
        assertNull(ber.prevSection(Ref.parse(catalog, "Berakhot 2a")))
        val hag = catalog.bookByTitle("Pesach Haggadah")!!
        assertEquals("Pesach_Haggadah,_Magid,_Ha_Lachma_Anya", hag.nextSection(Ref.parse(catalog, "Pesach Haggadah, Kadesh"))?.url())
        assertEquals("Pesach_Haggadah,_Magid,_Ha_Lachma_Anya", Ref.parse(catalog, "Pesach Haggadah, Magid").openSection().url())
        assertEquals("Genesis.2.5", Ref.parse(catalog, "Genesis 2").segment(5).url())
    }

    private data class Quad(val a: String, val b: Int, val c: String, val d: String)
    private operator fun <T> List<T>.component6() = this[5]
}
