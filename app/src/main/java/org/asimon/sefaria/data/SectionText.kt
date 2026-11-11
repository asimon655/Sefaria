package org.asimon.sefaria.data

import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.data.remote.VersionDto

/** One section of text in both languages; segment i has number first + i. */
data class SectionText(
    val ref: String,
    val heRef: String,
    val first: Int,
    val he: List<String>,
    val en: List<String>,
    val versions: Map<String, VersionDto>,
    val linkCounts: List<Int> = emptyList(),
) {
    val size get() = maxOf(he.size, en.size)

    init {
        // slot i is the same segment in both languages (SERVER_SPEC §4.2): a section's two texts
        // and its link counts are one line per slot, or absent
        debugAssert(first >= 1) { "$ref: its first segment is numbered $first" }
        debugAssert(he.isEmpty() || en.isEmpty() || he.size == en.size) { "$ref: ${he.size} Hebrew segments, ${en.size} English" }
        debugAssert(linkCounts.isEmpty() || linkCounts.size == size) { "$ref: ${linkCounts.size} link counts for $size segments" }
        debugAssert(linkCounts.all { it >= 0 }) { "$ref: a negative link count" }
    }
}
