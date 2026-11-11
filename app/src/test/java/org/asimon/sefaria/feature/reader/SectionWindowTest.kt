package org.asimon.sefaria.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionWindowTest {
    // sections are their own row counts here
    private fun window(max: Int, margin: Int = 0, vararg sections: Int) =
        SectionWindow<Int>(max, margin).also { w ->
            w.reset(sections.first())
            for (s in sections.drop(1)) w.append(s, 0..0, { it })
        }

    @Test
    fun growsAtBothEnds() {
        val w = SectionWindow<String>(4)
        w.reset("b")
        assertEquals(emptyList<String>(), w.append("c", 0..3, { 10 }))
        assertEquals(emptyList<String>(), w.prepend("a", 0..3, { 10 }))
        assertEquals(listOf("a", "b", "c"), w.sections)
    }

    @Test
    fun appendingDropsTheStartOnceItIsBehindTheReader() {
        val w = window(3, 0, 10, 10, 10) // rows 0..29
        // reading rows 25..29: sections 1 and 2 (rows 0..19) are behind; only one is over the limit
        assertEquals(listOf(10), w.append(7, 25..29, { it }))
        assertEquals(listOf(10, 10, 7), w.sections)
    }

    @Test
    fun appendingKeepsWhatIsOnScreen() {
        val w = window(2, 0, 10, 10)
        // the first section is still on screen (row 9): over the limit, but it stays
        assertEquals(emptyList<Int>(), w.append(10, 9..19, { it }))
        assertEquals(3, w.sections.size)
        // and the margin keeps a section that ended just above the screen
        val m = window(2, 5, 10, 10)
        assertEquals(emptyList<Int>(), m.append(10, 12..19, { it }))
        assertEquals(listOf(10), m.append(10, 15..29, { it }))
    }

    @Test
    fun appendingDropsSeveralWhenFarBehind() {
        val w = window(2, 0, 10, 10)
        w.append(10, 0..5, { it }) // 3 held: the reader was at the top, nothing could go
        assertEquals(listOf(10, 10), w.append(10, 25..29, { it }))
        assertEquals(2, w.sections.size)
    }

    @Test
    fun prependingDropsTheEndOnceItIsAheadOfTheReader() {
        val w = window(3, 0, 10, 10, 10)
        // reading rows 0..5 of the old list: the last section (rows 20..29) is far ahead
        assertEquals(listOf(10), w.prepend(4, 0..5, { it }))
        assertEquals(listOf(4, 10, 10), w.sections)
        // reading rows 15..21: the last section is on screen and stays
        val v = window(3, 0, 10, 10, 10)
        assertEquals(emptyList<Int>(), v.prepend(4, 15..21, { it }))
    }
}
