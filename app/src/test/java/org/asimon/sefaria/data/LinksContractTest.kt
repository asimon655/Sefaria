package org.asimon.sefaria.data

import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 contract: links JSON recorded from the server (full corpus build). */
class LinksContractTest {
    @Test
    fun genesis11() {
        val l = SefariaJson.decodeFromString<LinksDto>(fixture("links_Genesis.1.1.json"))
        assertEquals("Commentary", l.groups.first().category)
        val books = l.groups.flatMap { it.books }.associateBy { it.title }
        assertEquals(3, books.getValue("Rashi on Genesis").count)
        assertEquals(1, books.getValue("Onkelos Genesis").count)
        assertTrue(books.getValue("Ramban on Genesis").count >= 5)
        assertEquals(l.groups.sumOf { it.count }, l.groups.sumOf { g -> g.books.sumOf { it.count } })
    }

    @Test
    fun oneBookWithText() {
        val l = SefariaJson.decodeFromString<LinksDto>(fixture("links_Genesis.1.1_Rashi.json"))
        val rashi = l.groups.single().books.single()
        assertEquals(listOf("Rashi_on_Genesis.1.1.1", "Rashi_on_Genesis.1.1.2", "Rashi_on_Genesis.1.1.3"), rashi.links.map { it.ref })
        assertTrue(rashi.links.all { it.he.isNotEmpty() })
    }

    @Test
    fun textCarriesLinkCounts() {
        val t = SefariaJson.decodeFromString<TextDto>(fixture("text_Genesis.1.json"))
        val s = t.sections.single()
        assertEquals(s.he.size, s.linkCounts.size)
        assertTrue(s.linkCounts[0] > 100) // Genesis 1:1 is the most linked verse
    }
}
