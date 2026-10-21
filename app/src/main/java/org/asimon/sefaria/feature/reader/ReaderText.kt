package org.asimon.sefaria.feature.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.TextFlow

/*
 * What the reader's list shows. The view model produces one item per segment; in paragraph
 * flow the screen groups each section's segments into one paragraph row. Selection, the
 * connections panel and paging still work in segment terms through [ReaderRows].
 */

sealed interface ReaderRow {
    val key: String
}

data class HeaderRow(val item: HeaderItem) : ReaderRow {
    override val key get() = item.key
}

data class LineRow(val item: SegmentItem) : ReaderRow {
    override val key get() = item.key
}

/** A section's segments flowing as one paragraph (single-language layouts only). */
data class ParagraphRow(override val key: String, val segments: List<SegmentItem>) : ReaderRow

/** Rows plus the maps between rows and the view model's items. */
class ReaderRows(
    val rows: List<ReaderRow>,
    private val rowOfKey: Map<String, Int>, // segment or header key -> row index
    private val firstItem: IntArray, // row -> index of its first item
    private val lastItem: IntArray,
) {
    fun rowOf(key: String?): Int? = key?.let { rowOfKey[it] }
    // Rows past the end (the list's footer: loading, retry, end of book) stand for the last
    // item, not the first: mapping the footer to item 0 kept the next section from loading
    // when a jump landed at the end of a chapter.
    fun firstItemOf(row: Int) = firstItem.getOrElse(row) { if (row < 0) 0 else lastItem.lastOrNull() ?: 0 }
    fun lastItemOf(row: Int) = lastItem.getOrElse(row) { if (row < 0) 0 else lastItem.lastOrNull() ?: 0 }

    companion object {
        fun build(items: List<ReaderItem>, paragraphs: Boolean): ReaderRows {
            val rows = ArrayList<ReaderRow>()
            val rowOf = HashMap<String, Int>()
            val first = ArrayList<Int>()
            val last = ArrayList<Int>()
            var i = 0
            while (i < items.size) {
                when (val it = items[i]) {
                    is HeaderItem -> {
                        rowOf[it.key] = rows.size; rows += HeaderRow(it); first += i; last += i; i++
                    }
                    is SegmentItem -> if (!paragraphs) {
                        rowOf[it.key] = rows.size; rows += LineRow(it); first += i; last += i; i++
                    } else {
                        val start = i
                        val segs = ArrayList<SegmentItem>()
                        while (i < items.size && items[i] is SegmentItem) {
                            val seg = items[i] as SegmentItem
                            rowOf[seg.key] = rows.size
                            segs += seg
                            i++
                        }
                        // keyed by its first segment, which stays the same when other sections load
                        rows += ParagraphRow("p:" + segs.first().key, segs); first += start; last += i - 1
                    }
                }
            }
            return ReaderRows(rows, rowOf, first.toIntArray(), last.toIntArray())
        }
    }
}

/** Paragraph flow applies to the single-language layouts; bilingual layouts stay per line. */
fun Prefs.paragraphs() = flow == TextFlow.Paragraph && (layout == Layout.Hebrew || layout == Layout.English)

/** Where each segment sits in a laid-out paragraph. */
class ParagraphLayout(val layout: TextLayoutResult, val ranges: List<IntRange>, val segments: List<SegmentItem>) {
    fun segmentAt(pos: Offset): SegmentItem? {
        val off = layout.getOffsetForPosition(pos)
        val i = ranges.indexOfFirst { off in it }.takeIf { it >= 0 } ?: ranges.indexOfLast { it.first <= off }
        return segments.getOrNull(i)
    }

    /** The y of the line where a segment starts, for scrolling to it. */
    fun topOf(key: String): Int? {
        val i = segments.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
        return layout.getLineTop(layout.getLineForOffset(ranges[i].first)).toInt()
    }
}

/** Space above and below a paragraph: a segment's top in its row is this plus [ParagraphLayout.topOf]. */
val ParagraphPad = 6.dp

/**
 * A section as one justified paragraph with small verse numbers. Tapping selects the verse
 * under the finger, long-press starts a range selection; [mark] colours a verse's background.
 */
@Composable
fun ParagraphText(
    row: ParagraphRow,
    prefs: Prefs,
    style: TextStyle,
    mark: (SegmentItem) -> Color?,
    onTap: (SegmentItem) -> Unit,
    onLongPress: (SegmentItem) -> Unit,
    onLayout: (ParagraphLayout) -> Unit,
) {
    val hebrew = prefs.layout == Layout.Hebrew
    val numberColor = MaterialTheme.colorScheme.secondary
    val marks = row.segments.map(mark)
    val (text, ranges) = remember(row, hebrew, marks, numberColor) {
        val ranges = ArrayList<IntRange>(row.segments.size)
        val text = buildAnnotatedString {
            for ((i, seg) in row.segments.withIndex()) {
                val body: AnnotatedString = (if (hebrew) seg.he ?: seg.en else seg.en ?: seg.he)?.text ?: continue
                val start = length
                pushStyle(SpanStyle(color = numberColor, fontSize = 0.6.em, baselineShift = BaselineShift(0.35f)))
                append(seg.label)
                pop()
                append(' ')
                append(body)
                marks[i]?.let { addStyle(SpanStyle(background = it), start, length) }
                ranges += start until length
                append(' ')
            }
        }
        text to ranges
    }
    val laid = remember(row.key) { LayoutHolder() }
    // the gesture handler outlives recompositions: read the latest callbacks
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    Text(
        text,
        style = style.copy(textAlign = TextAlign.Justify),
        color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { l -> ParagraphLayout(l, ranges, row.segments).also { laid.value = it; onLayout(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = ParagraphPad)
            .pointerInput(row.key, ranges) {
                detectTapGestures(
                    onTap = { pos -> laid.value?.segmentAt(pos)?.let(tap) },
                    onLongPress = { pos -> laid.value?.segmentAt(pos)?.let(longPress) },
                )
            },
    )
}

/** The latest layout, for gesture handlers (not state: nothing redraws when it changes). */
private class LayoutHolder {
    var value: ParagraphLayout? = null
}

/**
 * Pinch with two fingers to resize the text; one finger still scrolls. [onScale] gets the
 * running factor during the gesture, [onEnd] when the fingers lift.
 */
fun Modifier.pinchToScale(onScale: (Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var factor = 1f
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                pinching = true
                factor *= event.calculateZoom()
                onScale(factor)
                event.changes.forEach { it.consume() } // the list doesn't scroll while pinching
            }
        } while (event.changes.any { it.pressed })
        if (pinching) onEnd()
    }
}

/** "בראשית יב:א–ה" for a selection of segments (one ref when they share a node). */
fun rangeLabel(first: SegmentItem, last: SegmentItem, hebrew: Boolean): String {
    val a = first.ref
    val b = last.ref
    if (a == b) return if (hebrew) a.hebrew() else a.human()
    if (a.book === b.book && a.node === b.node && a.sections.size == b.sections.size) {
        val r = Ref(a.book, a.node, a.sections, to = b.sections)
        return if (hebrew) r.hebrew() else r.human()
    }
    return if (hebrew) a.hebrew() + " – " + b.hebrew() else a.human() + " – " + b.human()
}
