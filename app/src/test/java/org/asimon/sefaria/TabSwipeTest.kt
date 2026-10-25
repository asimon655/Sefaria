package org.asimon.sefaria

import org.junit.Assert.assertEquals
import org.junit.Test

class TabSwipeTest {
    private fun step(dx: Float, velocity: Float = 0f, rtl: Boolean = false) = swipeStep(dx, velocity, distance = 100f, fling = 1000f, rtl = rtl)

    @Test
    fun aLongEnoughSwipeMovesOneTab() {
        assertEquals(1, step(-120f)) // finger to the left: the next tab comes in from the right
        assertEquals(-1, step(120f))
        assertEquals(0, step(-80f)) // too short
    }

    @Test
    fun aQuickFlingNeedsLessDistance() {
        assertEquals(1, step(-40f, velocity = -1500f))
        assertEquals(0, step(-20f, velocity = -1500f)) // under a third of the distance
        assertEquals(0, step(-40f, velocity = 1500f)) // flung back the other way
    }

    @Test
    fun rightToLeftTabsRunTheOtherWay() {
        assertEquals(-1, step(-120f, rtl = true))
        assertEquals(1, step(120f, rtl = true))
    }
}
