package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.lazy.LazyListLayoutInfo

/** A visible row as the snap rule sees it: its index and its top and bottom in viewport pixels. */
data class SnapItem(val index: Int, val top: Int, val bottom: Int) {
    val size get() = bottom - top
}

/**
 * Where to snap when a scroll ends while connections are open, as a row index to bring to the
 * top, or null to leave the list where it is.
 *
 * - The verse at the top of the viewport snaps back to its start, unless more than half of it
 *   has scrolled away, then the next verse snaps to the top.
 * - A verse taller than the viewport scrolls freely: no snap while you read inside it. Only
 *   once the next verse's top has passed the middle of the viewport does that verse snap up.
 * - Headers and other rows are skipped (a header at the top snaps to the verse after it).
 */
fun snapTarget(items: List<SnapItem>, viewportTop: Int, viewportBottom: Int, isVerse: (Int) -> Boolean): Int? {
    val verses = items.filter { isVerse(it.index) && it.bottom > viewportTop }
    val a = verses.firstOrNull() ?: return null
    val next = verses.getOrNull(1)
    val height = viewportBottom - viewportTop
    val middle = viewportTop + height / 2
    if (a.top >= viewportTop) {
        // the verse starts inside the viewport (a header above it): align it, unless it already is
        return a.index.takeIf { a.top - viewportTop > 2 }
    }
    if (a.size > height) {
        // inside a long verse: free, until the next verse has come up past the middle
        return if (next != null && next.top < middle) next.index else null
    }
    val visible = a.bottom - viewportTop
    return if (visible * 2 < a.size && next != null) next.index else a.index
}

/** [snapTarget] on a LazyList's current layout. */
fun snapTarget(info: LazyListLayoutInfo, isVerse: (Int) -> Boolean): Int? {
    val items = info.visibleItemsInfo.map { SnapItem(it.index, it.offset, it.offset + it.size) }
    return snapTarget(items, info.viewportStartOffset, info.viewportEndOffset, isVerse)
}
