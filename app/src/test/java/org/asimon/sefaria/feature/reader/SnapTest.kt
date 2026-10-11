package org.asimon.sefaria.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Viewport 0..1000; rows are verses unless listed in [headers]. */
class SnapTest {
    private fun snap(vararg rows: Triple<Int, Int, Int>, headers: Set<Int> = emptySet()) =
        snapTarget(rows.map { SnapItem(it.first, it.second, it.third) }, 0, 1000) { it !in headers }

    @Test
    fun alignedStaysPut() = assertNull(snap(Triple(3, 0, 200), Triple(4, 200, 500)))

    @Test
    fun snapsBackWhenLessThanHalfScrolledAway() = assertEquals(3, snap(Triple(3, -80, 200), Triple(4, 200, 500)))

    @Test
    fun snapsForwardWhenMoreThanHalfScrolledAway() = assertEquals(4, snap(Triple(3, -150, 50), Triple(4, 50, 400)))

    @Test
    fun headerAboveSnapsToTheVerse() = assertEquals(6, snap(Triple(5, -20, 60), Triple(6, 60, 300), headers = setOf(5)))

    @Test
    fun longVerseScrollsFreely() {
        // inside a 2400px verse, its end below the viewport: no snap
        assertNull(snap(Triple(7, -900, 1500)))
        // its end visible but the next verse still in the lower half: no snap
        assertNull(snap(Triple(7, -1700, 700), Triple(8, 700, 900)))
        // the next verse has come up past the middle: snap to it
        assertEquals(8, snap(Triple(7, -2000, 400), Triple(8, 400, 700)))
    }
}
