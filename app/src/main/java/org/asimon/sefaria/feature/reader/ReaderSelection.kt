package org.asimon.sefaria.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.Layout

/**
 * A range selection in the reader: a long-press [start]s it at a segment, a tap [extendTo]s it to
 * another. [anchor] and [end] are segment keys, in either order; null when nothing is selected.
 */
@Stable
class ReaderSelection(anchor: String? = null, end: String? = null) {
    var anchor by mutableStateOf(anchor)
        private set
    var end by mutableStateOf(end)
        private set

    fun start(key: String) {
        anchor = key
        end = key
    }

    fun extendTo(key: String) {
        end = key
    }

    fun clear() {
        anchor = null
        end = null
    }

    /** The keys of the segments from [anchor] to [end] among [items]; empty when nothing is selected. */
    fun keys(items: List<ReaderItem>): Set<String> {
        val index = items.withIndex().associate { (i, it) -> it.key to i }
        val a = index[anchor] ?: return emptySet()
        val e = index[end] ?: a
        return (minOf(a, e)..maxOf(a, e)).mapNotNull { (items[it] as? SegmentItem)?.key }.toSet()
    }

    companion object {
        val Saver = listSaver<ReaderSelection, String?>({ listOf(it.anchor, it.end) }, { ReaderSelection(it[0], it[1]) })
    }
}

@Composable
fun rememberReaderSelection(): ReaderSelection = rememberSaveable(saver = ReaderSelection.Saver) { ReaderSelection() }

/** Selected segments as one ref (a segment, or a range within one node), for bookmarking; null for none. */
fun selectionRef(segs: List<SegmentItem>): Ref? {
    if (segs.isEmpty()) return null
    val a = segs.first().ref
    val b = segs.last().ref
    if (a == b) return a
    return if (a.node === b.node && a.sections.size == b.sections.size) Ref(a.book, a.node, a.sections, to = b.sections) else a
}

/** The first selected segment's text, for a bookmark's preview. */
fun selectionPreview(segs: List<SegmentItem>): String = segs.firstOrNull()?.let { (it.he ?: it.en)?.plain }.orEmpty()

/**
 * Selected segments as text to copy or share: each segment in the reader's [layout] (both
 * languages, Hebrew first, for the two-language layouts), then "— " and the range ([hebrew]: its
 * Hebrew form). "" for none.
 */
fun selectionCitation(segs: List<SegmentItem>, layout: Layout, hebrew: Boolean): String {
    if (segs.isEmpty()) return ""
    return buildString {
        for (seg in segs) {
            val body = when (layout) {
                Layout.Hebrew -> (seg.he ?: seg.en)?.plain
                Layout.English -> (seg.en ?: seg.he)?.plain
                else -> listOfNotNull(seg.he?.plain, seg.en?.plain).joinToString("\n")
            }
            if (!body.isNullOrBlank()) append(body).append('\n')
        }
        append("— ").append(rangeLabel(segs.first(), segs.last(), hebrew))
    }
}
