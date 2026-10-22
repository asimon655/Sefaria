package org.asimon.sefaria.core

import org.asimon.sefaria.core.ref.Book

/**
 * How connections are grouped and ordered (SERVER_SPEC §6.5), as the server's api/links.go does:
 * commentaries on the source book first, then targumim, then the target books' top categories in
 * TOC order, then commentaries on other books that quote the source.
 */
object LinkGrouping {
    const val COMMENTARY = "Commentary"
    const val QUOTING = "Quoting Commentary"
    const val TARGUM = "Targum"
    const val OTHER = "Other"

    private val HE = mapOf(COMMENTARY to "מפרשים", QUOTING to "מפרשים מצטטים", TARGUM to "תרגומים", OTHER to "אחר")

    /** The group of a link from the book titled [source] to [dst]. */
    fun group(source: String, dst: Book): String = when (dst.dto.dependence.lowercase()) {
        "commentary" -> if (source in dst.dto.baseTextTitles) COMMENTARY else QUOTING
        "targum" -> TARGUM
        else -> dst.dto.categories.firstOrNull() ?: OTHER
    }

    /** A group's Hebrew name; [categoryHe] names a top category (the TOC's), "" if unknown. */
    fun he(group: String, categoryHe: (String) -> String): String = HE[group] ?: categoryHe(group)

    /** Where a group sorts; [categoryOrder] is each top category's TOC position. Ties sort by name. */
    fun order(group: String, categoryOrder: Map<String, Int>): Int = when (group) {
        COMMENTARY -> 0
        TARGUM -> 1
        QUOTING -> 1 shl 20
        else -> categoryOrder[group]?.let { 10 + it } ?: (1 shl 19)
    }
}
