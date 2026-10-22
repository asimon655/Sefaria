package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A snap point: [item] in viewport pixels, the list row it is in and its top inside the row, and its segment. */
data class VerseSpot(val item: SnapItem, val row: Int, val inRow: Int, val key: String)

/**
 * Where the reader's text list is, in segments: scrolling to a segment (inside its paragraph in
 * paragraph flow), and the verses on screen as the snap rule sees them (Snap.kt). [rows] are the
 * rows as last composed.
 */
@Stable
class ReaderScroll(
    val list: LazyListState,
    private val currentRows: State<ReaderRows>,
    private val paragraphPad: Int,
    /** Where a selected verse settles: a little below the top, keeping the previous verse's end in view. */
    val snapLine: Int,
) {
    val rows: ReaderRows get() = currentRows.value

    /** Each paragraph row's text layout, by row key, as the paragraphs report them. */
    val paragraphLayouts = mutableMapOf<String, ParagraphLayout>()

    /** Scrolls so that a segment is at the top (inside its paragraph in paragraph flow), [line] pixels above it. */
    suspend fun scrollTo(key: String, animate: Boolean, line: Int = 0) {
        val rows = rows
        val row = rows.rowOf(key) ?: return
        val offset = ((rows.rows[row] as? ParagraphRow)?.let { p -> paragraphLayouts[p.key]?.topOf(key)?.let { it + paragraphPad } } ?: 0) - line
        if (animate) list.animateScrollToItem(row, offset) else list.scrollToItem(row, offset)
    }

    /**
     * The verses on screen as the snap rule sees them: a verse-per-line row is one; in a
     * paragraph, each line where a verse starts is one (verses starting on the same line share
     * it, and the first of them stands for it), down to the next such line.
     */
    fun verseSpots(): List<VerseSpot> {
        val out = ArrayList<VerseSpot>()
        for (info in list.layoutInfo.visibleItemsInfo) {
            when (val row = rows.rows.getOrNull(info.index)) {
                is LineRow -> out += VerseSpot(SnapItem(out.size, info.offset, info.offset + info.size), info.index, 0, row.item.key)
                is ParagraphRow -> {
                    val laid = paragraphLayouts[row.key] ?: continue
                    val starts = row.segments.mapNotNull { seg -> laid.topOf(seg.key)?.let { seg.key to it + paragraphPad } }
                        .distinctBy { it.second }
                    for ((j, start) in starts.withIndex()) {
                        val (key, top) = start
                        val bottom = starts.getOrNull(j + 1)?.second ?: (info.size - paragraphPad)
                        out += VerseSpot(SnapItem(out.size, info.offset + top, info.offset + bottom), info.index, top, key)
                    }
                }
                else -> {}
            }
        }
        return out
    }

    /** The segment at the top of the text: with snapping, the one at the snap line. Null if none is laid out. */
    fun segmentAtTop(snap: Boolean): String? {
        if (snap) {
            val at = list.layoutInfo.viewportStartOffset + snapLine
            return verseSpots().firstOrNull { it.item.bottom > at + 1 }?.key
        }
        for (info in list.layoutInfo.visibleItemsInfo) {
            val key = when (val row = rows.rows.getOrNull(info.index)) {
                is LineRow -> row.item.key.takeIf { info.offset >= -info.size / 3 }
                is ParagraphRow -> paragraphLayouts[row.key]?.segmentAt(Offset(1f, maxOf(0, -info.offset) + 1f))?.key
                    ?: row.segments.first().key
                else -> null
            } ?: continue
            return key
        }
        return null
    }

    /** Scrolls to the verse the snap rule picks (Snap.kt), if any. */
    suspend fun snap() {
        val spots = verseSpots()
        val info = list.layoutInfo
        val target = snapTarget(spots.map { it.item }, info.viewportStartOffset, info.viewportEndOffset, snapLine) ?: return
        val spot = spots[target]
        list.animateScrollToItem(spot.row, spot.inRow - snapLine)
    }
}

@Composable
fun rememberReaderScroll(list: LazyListState, rows: ReaderRows): ReaderScroll {
    val current = rememberUpdatedState(rows)
    val density = LocalDensity.current
    val pad = with(density) { ParagraphPad.roundToPx() }
    val snapLine = with(density) { 32.dp.roundToPx() }
    return remember(list, pad, snapLine) { ReaderScroll(list, current, pad, snapLine) }
}

/**
 * Scrolls to the item at [initialIndex] once it is known, then reports the items on screen
 * ([onVisible], first and last) as the list scrolls, so the view model pages in both directions;
 * [onInitialDone] tells it paging may start.
 */
@Composable
fun PageAsScrolled(
    scroll: ReaderScroll,
    initialIndex: Int?,
    items: List<ReaderItem>,
    onInitialDone: () -> Unit,
    onVisible: (first: Int, last: Int) -> Unit,
) {
    val list = scroll.list
    LaunchedEffect(initialIndex) {
        val idx = initialIndex ?: return@LaunchedEffect
        val key = items.getOrNull(idx)?.key ?: return@LaunchedEffect
        val rows = scroll.rows
        val row = rows.rowOf(key) ?: return@LaunchedEffect
        val rowKey = rows.rows[row].key
        list.scrollToItem(row)
        // page only once the target is laid out; a prepend before that would shift the
        // pending index into the previous section
        withTimeoutOrNull(2000) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == rowKey } }.first { it }
        }
        if (rows.rows[row] is ParagraphRow) scroll.scrollTo(key, animate = false)
        onInitialDone()
        onVisible(rows.firstItemOf(list.firstVisibleItemIndex), rows.lastItemOf(list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: row))
    }
    LaunchedEffect(list) {
        // the row count too: a section that loads while nothing on screen moves (a short one, or
        // the list already at its end) is checked again for the next one
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, scroll.rows.rows.size) }
            .collect { (first, last, _) -> onVisible(scroll.rows.firstItemOf(first), scroll.rows.lastItemOf(last)) }
    }
}

/**
 * While connections are open ([active]) they follow the reading: when a scroll ends, the segment
 * at the top of the text becomes the selected one ([onSelect]); with [snap], the scroll then
 * settles on a verse (Snap.kt).
 */
@Composable
fun FollowReading(scroll: ReaderScroll, active: Boolean, snap: Boolean, onSelect: (String) -> Unit) {
    val list = scroll.list
    LaunchedEffect(list, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress }.drop(1).filter { !it }.collect {
            scroll.segmentAtTop(snap)?.let(onSelect)
        }
    }
    // Runs after the follow effect above has seen the gesture end, and its own scroll ends on a verse top.
    LaunchedEffect(list, active, snap) {
        if (!active || !snap) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress }.drop(1).filter { !it }.collect { scroll.snap() }
    }
}
