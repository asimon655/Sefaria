package org.asimon.sefaria.data.repo

import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.local.LibraryReader

/**
 * Section text from the downloaded library (SERVER_SPEC §7), as /v1/text returns it, with its link
 * counts from [links]. Null when the book is not downloaded and the server should answer.
 */
class OfflineText(private val reader: LibraryReader, private val links: OfflineLinks) {
    /** A section of a downloaded book in its default versions; null if the book is not downloaded. */
    suspend fun section(section: Ref): SectionText? {
        val book = section.book
        val f = reader.book(book.title) ?: return null
        val range = runCatching { section.slots() }.getOrNull() ?: return null
        val start = range.first
        val end = range.last + 1
        val seg = Ref.at(book, start)
        val sec = seg.section()
        val he = f.text("he")?.let { reader.lines(f, it, start, end) ?: return null }.orEmpty()
        val en = f.text("en")?.let { reader.lines(f, it, start, end) ?: return null }.orEmpty()
        return SectionText(
            ref = sec.url(), heRef = sec.hebrew(), first = if (seg.sections.isNotEmpty()) seg.number() else 1,
            he = he, en = en, versions = book.dto.versions.filter { it.default }.associateBy { it.lang },
            linkCounts = links.counts(book.title, start, end).orEmpty(),
        )
    }
}
