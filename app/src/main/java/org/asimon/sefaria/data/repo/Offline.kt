package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.ref.fromSlots
import org.asimon.sefaria.core.text.Clause
import org.asimon.sefaria.core.text.Kind
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.data.local.BookReader
import org.asimon.sefaria.data.local.IndexFile
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.local.LinkRecords
import org.asimon.sefaria.data.local.LinksFile
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinkBookDto
import org.asimon.sefaria.data.remote.LinkDto
import org.asimon.sefaria.data.remote.LinkGroupDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.remote.SefariaJson

/**
 * Reading from the downloaded library (SERVER_SPEC §7) with the server's own rules, so
 * offline answers match online ones: section text (/v1/text), link counts and connections
 * (/v1/links), and full-text search (/v1/search) by scanning the downloaded text. Each
 * method returns null when the library can't answer and the caller should ask the server.
 */
class OfflineLibrary(private val store: LibraryStore) {
    private val lock = Mutex()
    private val linkBooks = object : LinkedHashMap<Int, Book>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Book>?) = size > 256
    }

    /** A section of a downloaded book in its default versions; null if the book is not downloaded. */
    suspend fun section(section: Ref): SectionText? {
        val book = section.book
        val f = store.book(book.title) ?: return null
        val range = runCatching { section.slots() }.getOrNull() ?: return null
        val start = range.first
        val end = range.last + 1
        val seg = Ref.at(book, start)
        val sec = seg.section()
        val he = f.text("he")?.let { store.lines(f, it, start, end) ?: return null }.orEmpty()
        val en = f.text("en")?.let { store.lines(f, it, start, end) ?: return null }.orEmpty()
        return SectionText(
            ref = sec.url(), heRef = sec.hebrew(), first = if (seg.sections.isNotEmpty()) seg.number() else 1,
            he = he, en = en, versions = book.dto.versions.filter { it.default }.associateBy { it.lang },
            linkCounts = counts(book.title, start, end).orEmpty(),
        )
    }

    /** Links touching each slot of [start, end), as a segment query lists them (section-level links not counted). */
    suspend fun counts(title: String, start: Int, end: Int): List<Int>? {
        val l = store.linksFile() ?: return null
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
        val l = store.linksFile() ?: return@withContext null
        val src = ref.book
        val recs = records(l, src.title) ?: return@withContext null
        val range = runCatching { ref.slots() }.getOrNull() ?: return@withContext null
        val start = range.first
        val end = range.last + 1
        if (end - start > MAX_SEGMENTS) return@withContext null
        val onlyIdx = only?.let { l.indexOf(it) ?: return@withContext null }
        val onlyFile = if (withText && only != null) store.book(only) ?: return@withContext null else null

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
        val groups = HashMap<String, GroupOut>()
        val books = HashMap<Int, BookOut>()
        var texts = 0
        for (i in kept) {
            val di = recs.dstBook[i]
            if (onlyIdx != null && di != onlyIdx) continue
            val dst = linkBook(l, di) ?: continue
            val g = group(src, dst)
            val lg = groups.getOrPut(g) { GroupOut(g, GROUP_HE[g] ?: l.header.categories.firstOrNull { it.en == g }?.he.orEmpty()) }
            val lb = books.getOrPut(di) { BookOut(dst.dto).also { lg.books += di to it } }
            val target = dst.fromSlots(recs.dstStart[i], recs.dstEnd[i])
            var he = emptyList<String>()
            var en = emptyList<String>()
            if (onlyFile != null && texts < MAX_TEXT_LINKS) {
                texts++
                val e = minOf(recs.dstEnd[i], recs.dstStart[i] + MAX_LINK_SEGMENTS)
                he = onlyFile.text("he")?.let { store.lines(onlyFile, it, recs.dstStart[i], e) }.orEmpty()
                en = onlyFile.text("en")?.let { store.lines(onlyFile, it, recs.dstStart[i], e) }.orEmpty()
            }
            lb.links += recs.dstStart[i] to LinkDto(
                anchor = src.fromSlots(recs.srcStart[i], recs.srcEnd[i]).url(),
                ref = target.url(), human = target.human(), heRef = target.hebrew(),
                type = l.header.linkTypes.getOrNull(recs.typeId(i)).orEmpty(), he = he, en = en,
            )
            lg.count++
        }
        val order = l.header.categories.withIndex().associate { (i, c) -> c.en to i }
        fun groupOrder(g: String) = when (g) {
            COMMENTARY -> 0
            TARGUM -> 1
            QUOTING -> 1 shl 20
            else -> order[g]?.let { 10 + it } ?: (1 shl 19)
        }
        LinksDto(
            ref = ref.url(),
            groups = groups.values.sortedWith(compareBy({ groupOrder(it.category) }, { it.category })).map { g ->
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

    /**
     * Full-text search in one downloaded book (SERVER_SPEC §6.6): up to [limit] hits after slot
     * [after] in reading order, in [lang]'s default version, that match [q] in [tier] 1 (every
     * clause exactly) or 2 (the rest). The book's index file picks the blocks that may match;
     * without one (or for a word* clause) every block is read. Each hit comes with its slot (for
     * the paging cursor). Null if the book is not downloaded.
     */
    suspend fun search(book: Book, lang: String, q: List<Clause>, tier: Int, after: Int, limit: Int) =
        search(book.title, { book }, lang, q, tier, after, limit)

    // [book] is asked for only when there is a hit: decoding thousands of books' metadata per page
    // is what made searching the whole library slow. [reader] opens and reads the files: a search
    // session keeps them for the whole search, apart from the reader's caches.
    suspend fun search(
        title: String, book: suspend () -> Book?, lang: String, q: List<Clause>, tier: Int, after: Int, limit: Int,
        reader: BookReader = store,
    ): List<Pair<Int, SearchHitDto>>? =
        withContext(Dispatchers.Default) {
            val candidates = reader.index(title)?.let { candidateBlocks(it, lang, q, exact = tier == 1) }
            if (candidates != null && candidates.isEmpty()) return@withContext emptyList()
            val f = reader.book(title) ?: return@withContext null
            val t = f.text(lang) ?: return@withContext emptyList()
            val out = ArrayList<Pair<Int, SearchHitDto>>()
            val from = after + 1
            if (from >= t.slots) return@withContext out
            var b: Book? = null
            blocks@ for (bi in t.blockOf(from) until t.blocks.size) {
                if (candidates != null && bi !in candidates) continue
                ensureActive()
                val lines = reader.block(f, t, bi) ?: return@withContext null
                val first = t.first(bi)
                for (j in maxOf(from - first, 0) until lines.size) {
                    val line = lines[j]
                    if (line.isEmpty()) continue
                    val hit = SearchMatch.match(SearchMatch.tokens(line), q) ?: continue
                    if ((tier == 1) != (hit.kind == Kind.EXACT)) continue
                    val bk = b ?: book()?.also { b = it } ?: return@withContext null
                    val r = Ref.at(bk, first + j)
                    out += first + j to SearchHitDto(r.url(), r.human(), r.hebrew(), bk.title, SearchMatch.snippet(line, q), hit.kind.wire)
                    if (out.size == limit) break@blocks
                }
            }
            out
        }

    // The blocks that may hold a match: for each clause, the blocks holding every word (in one of
    // its forms, or only the word itself for [exact]), intersected across clauses. Null when a
    // clause can't be looked up (word*), and every block must be read.
    private fun candidateBlocks(ix: IndexFile, lang: String, q: List<Clause>, exact: Boolean): Set<Int>? {
        var acc: Set<Int>? = null
        for (c in q) {
            if (c.any { it.prefix }) return null
            for (w in c) {
                val alts = if (exact) listOf(w.text) else listOf(w.text) + w.forms.keys
                val blocks = HashSet<Int>()
                for (a in alts) ix.blocks(lang, a).forEach { blocks += it }
                acc = acc?.intersect(blocks) ?: blocks
                if (acc.isEmpty()) return acc
            }
        }
        return acc
    }

    // The links file answers for a book unless the downloaded book is another file than the one
    // its records were built against (slots may differ): then the server answers.
    private suspend fun records(l: LinksFile, title: String): LinkRecords? {
        val i = l.indexOf(title) ?: return null
        val installed = store.bookFile(title)
        if (installed != null && installed != l.header.books[i].file) return null
        return store.records(i)
    }

    private suspend fun linkBook(l: LinksFile, i: Int): Book? {
        lock.withLock { linkBooks[i] }?.let { return it }
        val json = store.linkMeta(i) ?: return null
        val b = Book(SefariaJson.decodeFromString(BookDto.serializer(), json))
        lock.withLock { linkBooks[i] = b }
        return b
    }

    private fun group(src: Book, dst: Book): String = when (dst.dto.dependence.lowercase()) {
        "commentary" -> if (src.title in dst.dto.baseTextTitles) COMMENTARY else QUOTING
        "targum" -> TARGUM
        else -> dst.dto.categories.firstOrNull() ?: "Other"
    }

    companion object {
        const val MAX_SEGMENTS = 2000
        const val MAX_TEXT_LINKS = 200
        const val MAX_LINK_SEGMENTS = 30
        private const val COMMENTARY = "Commentary"
        private const val QUOTING = "Quoting Commentary"
        private const val TARGUM = "Targum"
        private val GROUP_HE = mapOf(COMMENTARY to "מפרשים", QUOTING to "מפרשים מצטטים", TARGUM to "תרגומים", "Other" to "אחר")
    }
}
