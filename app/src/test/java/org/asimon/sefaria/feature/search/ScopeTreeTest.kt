package org.asimon.sefaria.feature.search

import org.asimon.sefaria.data.remote.TocNodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeTreeTest {
    private fun book(t: String) = TocNodeDto(kind = "book", title = t)
    private fun cat(en: String, vararg ch: TocNodeDto) = TocNodeDto(kind = "category", en = en, children = ch.toList())

    private val tree = ScopeTree(
        listOf(
            cat("Tanakh", cat("Torah", book("Genesis"), book("Exodus")), cat("Prophets", book("Joshua"))),
            cat("Liturgy", book("Siddur")),
        )
    )

    @Test
    fun pathsAndCounts() {
        assertEquals(listOf("c:Tanakh", "c:Tanakh/Torah", "b:Genesis", "b:Exodus", "c:Tanakh/Prophets", "b:Joshua", "c:Liturgy", "b:Siddur"), tree.entries.map { it.key })
        assertEquals(3, tree.count("Tanakh"))
        assertEquals(2, tree.count("Tanakh/Torah"))
        assertEquals("Tanakh/Torah", tree.pathOf("Genesis"))
        assertEquals(listOf("Tanakh", "Torah"), tree.entries.first { it.key == "b:Exodus" }.trail.map { it.en })
    }

    @Test
    fun tickingACategoryReplacesWhatItCovers() {
        var s = Scope(books = setOf("Genesis", "Siddur"), categories = setOf("Tanakh/Prophets"))
        s = tree.setCategory(s, "Tanakh", true)
        assertEquals(Scope(books = setOf("Siddur"), categories = setOf("Tanakh")), s)
        assertTrue(tree.covered(s, "Tanakh/Torah", isBook = false))
        assertTrue(tree.covered(s, "Tanakh/Torah", isBook = true)) // a book in Torah
        assertFalse(tree.covered(s, "Tanakh", isBook = false)) // the category itself is ticked, not locked
        assertEquals(Scope(books = setOf("Siddur")), tree.setCategory(s, "Tanakh", false))
    }

    @Test
    fun partly() {
        val s = Scope(books = setOf("Genesis"))
        assertTrue(tree.partly(s, "Tanakh"))
        assertTrue(tree.partly(s, "Tanakh/Torah"))
        assertFalse(tree.partly(s, "Tanakh/Prophets"))
        assertFalse(tree.partly(s, "Liturgy"))
        val c = Scope(categories = setOf("Tanakh/Torah"))
        assertTrue(tree.partly(c, "Tanakh"))
        assertFalse(tree.partly(c, "Tanakh/Torah")) // ticked, not partly
    }

    @Test
    fun compactSaysAScopeInAsFewNamesAsItTakes() {
        // every book of Torah: the category stands for them; Joshua completes Tanakh
        assertEquals(Scope(categories = setOf("Tanakh/Torah")), tree.compact(Scope(books = setOf("Genesis", "Exodus"))))
        assertEquals(Scope(categories = setOf("Tanakh")), tree.compact(Scope(books = setOf("Genesis", "Exodus", "Joshua"))))
        assertEquals(Scope(categories = setOf("Tanakh")), tree.compact(Scope(books = setOf("Joshua"), categories = setOf("Tanakh/Torah"))))
        // every book there is: the whole library, which is no names at all
        assertEquals(Scope(), tree.compact(Scope(books = setOf("Genesis", "Exodus", "Joshua", "Siddur"))))
        // part of a category stays as its books; a book the tree doesn't know stays too
        val part = Scope(books = setOf("Genesis", "Siddur", "Unlisted"))
        assertEquals(Scope(books = setOf("Genesis", "Unlisted"), categories = setOf("Liturgy")), tree.compact(part))
        assertEquals(Scope(categories = setOf("Liturgy")), tree.compact(Scope(categories = setOf("Liturgy"))))
    }

    @Test
    fun aScopeSurvivesBeingWrittenAsAString() {
        val downloaded = setOf("Genesis", "Siddur")
        for (s in listOf(
            Scope(),
            Scope(books = setOf("Genesis")),
            Scope(books = setOf("Genesis", "Joshua"), categories = setOf("Tanakh/Torah", "Liturgy")),
            Scope(books = downloaded),
        )) assertEquals(s, Scope.decode(s.encode(downloaded), downloaded))
        assertEquals("", Scope().encode(downloaded))
        // the downloaded books are one word, whatever they are when it's read back
        val e = Scope(books = downloaded).encode(downloaded)
        assertEquals("d", e)
        assertTrue(Scope.needsDownloaded(e))
        assertEquals(Scope(books = setOf("Genesis", "Siddur", "Exodus")), Scope.decode(e, downloaded + "Exodus"))
        // a category beside them isn't that choice
        assertFalse(Scope.needsDownloaded(Scope(downloaded, setOf("Liturgy")).encode(downloaded)))
    }

    @Test
    fun typedLettersAreMarkedInASuggestion() {
        val m = marked("Shulchan Arukh", "aru")
        assertEquals("Shulchan Arukh", m.text)
        assertEquals(listOf(9 to 12), m.spanStyles.map { it.start to it.end })
        assertTrue(marked("Genesis", "xyz").spanStyles.isEmpty())
        assertTrue(marked("Genesis", "").spanStyles.isEmpty())
    }
}
