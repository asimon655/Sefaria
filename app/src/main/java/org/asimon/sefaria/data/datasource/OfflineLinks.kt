package org.asimon.sefaria.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.LinkGrouping
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.ref.fromSlots
import org.asimon.sefaria.data.local.LibraryReader
import org.asimon.sefaria.data.local.LinkRecords
import org.asimon.sefaria.data.local.LinksFile
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinkBookDto
import org.asimon.sefaria.data.remote.LinkDto
import org.asimon.sefaria.data.remote.LinkGroupDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SefariaJson

/**
 * Connections from the downloaded links file (SERVER_SPEC §7), with the server's rules so offline
 * answers match /v1/links: per-segment link counts and grouped connections. Each method returns
 * null when the library can't answer and the caller should ask the server.
 */
class OfflineLinks(private val reader: LibraryReader) {
    private val lock = Mutex()
    private val linkBooks = object : LinkedHashMap<Int, Book>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Book>?) = size > 256
    }

    /** Links touching each slot of [start, end), as a segment query lists them (section-level links not counted). */
    suspend fun counts(title: String, start: Int, end: Int): List<Int>? {
        val l = reader.linksFile() ?: return null
        val recs = records(l, title) ?: return null
        val n = IntArray(end - start)
        for (i in recs.overlapping(start, end)) {
            if (recs.srcCoarse(i)) continue
            for (slot in maxOf(start, recs.srcStart[i]) until minOf(end, recs.srcEnd[i])) n[slot - start]++
        }
        return n.toList()
    }

    /**
     * The connections of [ref] grouped as /v1/links returns them; [only] keeps one book's, and
     * [withText] adds their text, which needs that book downloaded. Null when the links file
     * can't answer (not downloaded, or built for another file of this book).
     */
    suspend fun links(ref: Ref, only: String? = null, withText: Boolean = false): LinksDto? = withContext(Dispatchers.Default) {
        val l = reader.linksFile() ?: return@withContext null
        val src = ref.book
        val recs = records(l, src.title) ?: return@withContext null
        val range = runCatching { ref.slots() }.getOrNull() ?: return@withContext null
        val start = range.first
        val end = range.last + 1
        if (end - start > MAX_SEGMENTS) return@withContext null
        val onlyIdx = only?.let { l.indexOf(it) ?: return@withContext null }
        val onlyFile = if (withText && only != null) reader.book(only) ?: return@withContext null else null

        val segmentQuery = ref.node.isJagged && ref.sections.size == ref.node.addressTypes.size
        val picked = recs.overlapping(start, end)
            .filter { !segmentQuery || !recs.srcCoarse(it) }
            .sortedWith(compareBy({ recs.dstBook[it] }, { recs.dstStart[it] }, { recs.dstEnd[it] }))
        // one target reached from several segments of the range is listed once
        val kept = ArrayList<Int>(picked.size)
        for (i in picked) {
            val p = kept.lastOrNull()
            if (p == null || recs.dstBook[p] != recs.dstBook[i] || recs.dstStart[p] != recs.dstStart[i] || recs.dstEnd[p] != recs.dstEnd[i]) kept += i
        }

        class GroupOut(val category: String, val he: String) {
            val books = ArrayList<Pair<Int, BookOut>>()
            var count = 0
        }
        val categoryHe = { g: String -> l.header.categories.firstOrNull { it.en == g }?.he.orEmpty() }
        val groups = HashMap<String, GroupOut>()
        val books = HashMap<Int, BookOut>()
        var texts = 0
        for (i in kept) {
            val di = recs.dstBook[i]
            if (onlyIdx != null && di != onlyIdx) continue
            val dst = linkBook(di) ?: continue
            val g = LinkGrouping.group(src.title, dst)
            val lg = groups.getOrPut(g) { GroupOut(g, LinkGrouping.he(g, categoryHe)) }
            val lb = books.getOrPut(di) { BookOut(dst.dto).also { lg.books += di to it } }
            val target = dst.fromSlots(recs.dstStart[i], recs.dstEnd[i])
            var he = emptyList<String>()
            var en = emptyList<String>()
            if (onlyFile != null && texts < MAX_TEXT_LINKS) {
                texts++
                val e = minOf(recs.dstEnd[i], recs.dstStart[i] + MAX_LINK_SEGMENTS)
                he = onlyFile.text("he")?.let { reader.lines(onlyFile, it, recs.dstStart[i], e) }.orEmpty()
                en = onlyFile.text("en")?.let { reader.lines(onlyFile, it, recs.dstStart[i], e) }.orEmpty()
            }
            lb.links += recs.dstStart[i] to LinkDto(
                anchor = src.fromSlots(recs.srcStart[i], recs.srcEnd[i]).url(),
                ref = target.url(), human = target.human(), heRef = target.hebrew(),
                type = l.header.linkTypes.getOrNull(recs.typeId(i)).orEmpty(), he = he, en = en,
            )
            lg.count++
        }
        val order = l.header.categories.withIndex().associate { (i, c) -> c.en to i }
        LinksDto(
            ref = ref.url(),
            groups = groups.values.sortedWith(compareBy({ LinkGrouping.order(it.category, order) }, { it.category })).map { g ->
                LinkGroupDto(
                    category = g.category, en = g.category, he = g.he, count = g.count,
                    books = g.books.sortedBy { it.first }.map { (_, b) ->
                        LinkBookDto(b.dto.title, b.dto.he, b.dto.collectiveTitle, b.links.size, b.links.sortedBy { it.first }.map { it.second })
                    },
                )
            },
        )
    }

    private class BookOut(val dto: BookDto) {
        val links = ArrayList<Pair<Int, LinkDto>>() // (target start, link)
    }

    // The links file answers for a book unless the downloaded book is another file than the one
    // its records were built against (slots may differ): then the server answers.
    private suspend fun records(l: LinksFile, title: String): LinkRecords? {
        val i = l.indexOf(title) ?: return null
        val installed = reader.bookFile(title)
        if (installed != null && installed != l.header.books[i].file) return null
        return reader.records(i)
    }

    private suspend fun linkBook(i: Int): Book? {
        lock.withLock { linkBooks[i] }?.let { return it }
        val json = reader.linkMeta(i) ?: return null
        val b = Book(SefariaJson.decodeFromString(BookDto.serializer(), json))
        lock.withLock { linkBooks[i] = b }
        return b
    }

    companion object {
        const val MAX_SEGMENTS = 2000
        const val MAX_TEXT_LINKS = 200
        const val MAX_LINK_SEGMENTS = 30
    }
}
