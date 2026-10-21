package org.asimon.sefaria.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailTest {
    @Test
    fun roundTrips() {
        val steps = listOf(
            TrailStep("Genesis", "Genesis.1.2", "Genesis 1:2", "בראשית א:ב", "Tanakh"),
            TrailStep("Rashi on Genesis", "Rashi_on_Genesis.1.2.1", "Rashi on Genesis 1:2:1", "רש\"י על בראשית א:ב:א"),
        )
        assertEquals(steps, TrailStep.decode(TrailStep.encode(steps)))
        assertEquals("", TrailStep.encode(emptyList()))
        assertEquals(emptyList<TrailStep>(), TrailStep.decode(""))
        assertEquals(emptyList<TrailStep>(), TrailStep.decode("garbage"))
    }
}
