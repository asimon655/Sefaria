package org.asimon.sefaria.feature.reader

/**
 * A verse on screen as the snap rule sees it: its index and its top and bottom in viewport
 * pixels. A verse-per-line row, or in paragraph flow the stretch from a verse's first line to
 * the next verse's (ReaderScreen's verseSpots).
 */
data class SnapItem(val index: Int, val top: Int, val bottom: Int) {
    val size get() = bottom - top
}

/**
 * Where to snap when a scroll ends while connections are open, as an item index to bring to the
 * snap line, or null to leave the list where it is. The snap line is [line] pixels below the
 * viewport's top, so the end of the previous verse stays in view as context.
 *
 * - The verse at the snap line snaps back to its start, unless more than half of it has
 *   passed above the line, then the next verse snaps to the line.
 * - A verse taller than the space below the line scrolls freely: no snap while you read
 *   inside it. Only once the next verse's top has passed the middle does that verse snap.
 * - Headers and other rows are skipped (a header at the line snaps to the verse after it).
 */
fun snapTarget(items: List<SnapItem>, viewportTop: Int, viewportBottom: Int, line: Int, isVerse: (Int) -> Boolean = { true }): Int? {
    val at = viewportTop + line
    val verses = items.filter { isVerse(it.index) && it.bottom > at }
    val a = verses.firstOrNull() ?: return null
    val next = verses.getOrNull(1)
    val height = viewportBottom - at
    val middle = at + height / 2
    if (a.top >= at) {
        // the verse starts below the line (a header above it, or a scroll back): align it, unless it already is
        return a.index.takeIf { a.top - at > 2 }
    }
    if (a.size > height) {
        // inside a long verse: free, until the next verse has come up past the middle
        return if (next != null && next.top < middle) next.index else null
    }
    val below = a.bottom - at
    return if (below * 2 < a.size && next != null) next.index else a.index.takeIf { at - a.top > 2 }
}
