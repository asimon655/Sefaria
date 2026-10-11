package org.asimon.sefaria.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Viewport 0..1000, snap line at [line]; rows are verses unless listed in [headers]. */
class SnapTest {
    private fun snap(vararg rows: Triple<Int, Int, Int>, line: Int = 0, headers: Set<Int> = emptySet()) =
        snapTarget(rows.map { SnapItem(it.first, it.second, it.third) }, 0, 1000, line) { it !in headers }

    @Test
    fun alignedStaysPut() {
        assertNull(snap(Triple(3, 0, 200), Triple(4, 200, 500)))
        assertNull(snap(Triple(2, -150, 40), Triple(3, 40, 240), line = 40)) // previous verse's end shows above the line
    }

    @Test
    fun snapsBackWhenLessThanHalfPassed() {
        assertEquals(3, snap(Triple(3, -80, 200), Triple(4, 200, 500)))
        assertEquals(3, snap(Triple(3, -40, 200), Triple(4, 200, 500), line = 40))
    }

    @Test
    fun snapsForwardWhenMoreThanHalfPassed() {
        assertEquals(4, snap(Triple(3, -150, 50), Triple(4, 50, 400)))
        assertEquals(4, snap(Triple(3, -110, 90), Triple(4, 90, 400), line = 40))
    }

    @Test
    fun verseBelowTheLineIsPulledUp() {
        assertEquals(6, snap(Triple(5, -20, 60), Triple(6, 60, 300), headers = setOf(5)))
        assertEquals(4, snap(Triple(3, -200, 0), Triple(4, 120, 400), line = 40)) // a gap (header) above it
    }

    @Test
    fun longVerseScrollsFreely() {
        assertNull(snap(Triple(7, -900, 1500)))
        assertNull(snap(Triple(7, -1700, 700), Triple(8, 700, 900)))
        assertEquals(8, snap(Triple(7, -2000, 400), Triple(8, 400, 700)))
    }
}
