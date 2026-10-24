package org.asimon.sefaria.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.Clause
import org.asimon.sefaria.core.text.Kind
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.data.local.BookReader
import org.asimon.sefaria.data.local.IndexFile
import org.asimon.sefaria.data.remote.SearchHitDto

/**
 * Full-text search in one downloaded book (SERVER_SPEC §6.6), with the server's matcher, so
 * offline hits are the server's hits for the books on the phone. [reader] opens and reads the
 * files (LibraryReader for a one-off search; a search session keeps them for a whole search).
 */
class OfflineSearch(private val reader: BookReader) {
    /**
     * Up to [limit] hits after slot [after] in reading order, in [lang]'s default version, that match
     * [q] in [tier] 1 (every clause exactly) or 2 (the rest). The book's index file picks the blocks
     * that may match; without one (or for a word* clause) every block is read. Each hit comes with
     * its slot (for the paging cursor). Null if the book is not downloaded.
     */
    suspend fun search(book: Book, lang: String, q: List<Clause>, tier: Int, after: Int, limit: Int) =
        search(book.title, { book }, lang, q, tier, after, limit)

    // [book] is asked for only when there is a hit: decoding thousands of books' metadata per page
    // is what made searching the whole library slow. [reader] opens and reads the files: a search
    // session keeps them for the whole search, apart from the reader's caches.
    suspend fun search(
        title: String, book: suspend () -> Book?, lang: String, q: List<Clause>, tier: Int, after: Int, limit: Int,
        reader: BookReader = this.reader,
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
}
