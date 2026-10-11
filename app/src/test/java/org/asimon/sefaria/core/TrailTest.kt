package org.asimon.sefaria.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailTest {
    @Test
    fun roundTrips() {
        val steps = listOf(
            TrailStep("Genesis", "Genesis.1.2", "Genesis 1:2", "בראשית א:ב"),
            TrailStep("Rashi on Genesis", "Rashi_on_Genesis.1.2.1", "Rashi on Genesis 1:2:1", "רש\"י על בראשית א:ב:א"),
        )
        val encoded = steps.map { it.encode() }
        assertEquals(steps, TrailStep.decodeAll(encoded))
        assertEquals(encoded, TrailStep.split(TrailStep.join(encoded)))
        assertEquals(emptyList<String>(), TrailStep.split(""))
        assertNull(TrailStep.decode("garbage"))
    }
}
