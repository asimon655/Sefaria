package org.asimon.sefaria.data

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
}
