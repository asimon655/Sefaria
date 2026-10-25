package org.asimon.sefaria.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderScrollTest {
    @Test
    fun versesStartingOnOneLineShareASnapPoint() {
        // verses 2 and 3 start on the line at 40, 4 on the next
        val starts = listOf("v1" to 0, "v2" to 40, "v3" to 40, "v4" to 80)
        assertEquals(listOf(0 to listOf("v1"), 40 to listOf("v2", "v3"), 80 to listOf("v4")), lineStarts(starts))
    }

    @Test
    fun followingTheReadingKeepsATappedVerseOnItsLine() {
        val line = listOf("v2", "v3")
        // tapped the second verse of the line: it stays selected
        assertEquals("v3", followPick(line, keep = "v3"))
        // the selection is elsewhere: the line's first verse
        assertEquals("v2", followPick(line, keep = "v9"))
        assertEquals("v2", followPick(line, keep = null))
        assertNull(followPick(emptyList(), keep = "v3"))
    }
}
