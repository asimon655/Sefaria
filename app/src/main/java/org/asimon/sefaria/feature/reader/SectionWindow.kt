package org.asimon.sefaria.feature.reader

import org.asimon.sefaria.core.debugAssert

/**
 * The sections the reader holds: a run of neighbouring sections, in reading order, that grows at
 * either end as the reader nears it and lets go of the far end once it holds more than [max].
 * Without the limit a long sitting kept every section it had scrolled through, and each new one
 * rebuilt a longer list.
 *
 * A section is dropped only when it lies wholly outside the rows on screen, with [margin] rows to
 * spare, so nothing on screen or just off it ever goes; scrolling back loads it again (from the
 * cache or the downloaded book). [C] is a loaded section; the window knows it only by the number
 * of list rows it gives.
 */
class SectionWindow<C>(private val max: Int, private val margin: Int = 0) {
    private val list = ArrayList<C>()

    /** The loaded sections, in reading order. */
    val sections: List<C> get() = list

    val isEmpty get() = list.isEmpty()
    val first: C get() = list.first()
    val last: C get() = list.last()

    /** Starts over with one section. */
    fun reset(c: C) {
        list.clear()
        list.add(c)
    }

    /**
     * Adds the section after the last one, then drops sections from the start while there are
     * more than [max] and they end before the first row on screen ([visible], in rows of the list
     * as it was before this call; [rows] counts a section's). Returns what was dropped.
     */
    fun append(c: C, visible: IntRange, rows: (C) -> Int): List<C> {
        list.add(c)
        val dropped = ArrayList<C>()
        var firstVisible = visible.first
        while (list.size > max) {
            val n = rows(list.first())
            if (n > firstVisible - margin) break
            dropped += list.removeAt(0)
            firstVisible -= n
        }
        debugAssert(list.isNotEmpty() && list.last() === c) { "the window dropped the section it was given" }
        return dropped
    }

    /**
     * Adds the section before the first one, then drops sections from the end while there are more
     * than [max] and they start after the last row on screen. Returns what was dropped.
     */
    fun prepend(c: C, visible: IntRange, rows: (C) -> Int): List<C> {
        var total = list.sumOf(rows)
        list.add(0, c)
        val dropped = ArrayList<C>()
        while (list.size > max) {
            val n = rows(list.last())
            if (total - n <= visible.last + margin) break // its first row is on screen, or nearly
            dropped += list.removeAt(list.size - 1)
            total -= n
        }
        debugAssert(list.isNotEmpty() && list.first() === c) { "the window dropped the section it was given" }
        return dropped
    }
}
