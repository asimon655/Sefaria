package org.asimon.sefaria.core

import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's link grouping rules (api/links.go), checked against its responses. */
class LinkGroupingTest {
    private fun book(name: String) = Book(SefariaJson.decodeFromString(BookDto.serializer(), fixture("book_$name.json")))

    @Test
    fun groups() {
        val rashi = book("Rashi_on_Genesis")
        assertEquals(LinkGrouping.COMMENTARY, LinkGrouping.group("Genesis", rashi))
        assertEquals(LinkGrouping.QUOTING, LinkGrouping.group("Berakhot", rashi))
        assertEquals("Talmud", LinkGrouping.group("Genesis", book("Berakhot")))
    }

    @Test
    fun orderMatchesTheServer() {
        val server = SefariaJson.decodeFromString(LinksDto.serializer(), fixture("links_Genesis.1.1.json")).groups
        val special = setOf(LinkGrouping.COMMENTARY, LinkGrouping.TARGUM, LinkGrouping.QUOTING)
        // the server's top categories in TOC order, as the links file's header lists them
        val categoryOrder = server.map { it.category }.filter { it !in special }.withIndex().associate { (i, c) -> c to i }
        val shuffled = server.map { it.category }.shuffled(java.util.Random(1))
        val sorted = shuffled.sortedWith(compareBy({ LinkGrouping.order(it, categoryOrder) }, { it }))
        assertEquals(server.map { it.category }, sorted)
        // a category the TOC doesn't know goes after the known ones, before quoting commentaries
        val unknown = LinkGrouping.order("Unlisted", categoryOrder)
        assertTrue(unknown > categoryOrder.values.max() + 10 && unknown < LinkGrouping.order(LinkGrouping.QUOTING, categoryOrder))
    }

    @Test
    fun hebrewNames() {
        val server = SefariaJson.decodeFromString(LinksDto.serializer(), fixture("links_Genesis.1.1.json")).groups
        val categoryHe = server.associate { it.category to it.he }
        for (g in server) assertEquals(g.category, g.he, LinkGrouping.he(g.category) { categoryHe.getValue(it) })
        assertEquals("אחר", LinkGrouping.he(LinkGrouping.OTHER) { "" })
    }
}
