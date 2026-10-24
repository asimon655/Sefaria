package org.asimon.sefaria.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.Clause
import org.asimon.sefaria.core.text.Kind
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.core.text.Word
import org.asimon.sefaria.data.local.BookFilter
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
     * [q] in [tier] 1 (every clause exactly) or 2 (the rest). The book's word filter, if there is
     * one, rules out a book that lacks a word; then its index file picks the blocks that may match
     * (without one every block is read). Each hit comes with its slot (for the paging cursor). Null
     * if the book is not downloaded. [q] has no word* clause: offline search doesn't do prefixes.
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
            require(q.none { c -> c.any { it.prefix } }) { "word* is searched online only" }
            val exact = tier == 1
            if (reader.filter(title)?.let { mayMatch(it, q, exact) } == false) return@withContext emptyList()
            val candidates = reader.index(title)?.let { candidateBlocks(it, lang, q, exact) }
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

    // Whether the book may hold every word of every clause (the word itself for [exact], else
    // the word or one of its forms), by its word filter: false only if it can't match.
    private fun mayMatch(f: BookFilter, q: List<Clause>, exact: Boolean): Boolean =
        q.all { c -> c.all { w -> alternatives(w, exact).any(f::mayHold) } }

    private fun alternatives(w: Word, exact: Boolean) = if (exact) listOf(w.text) else listOf(w.text) + w.forms.keys

    // The blocks that may hold a match: for each clause, the blocks holding every word (in one of
    // its forms, or only the word itself for [exact]), intersected across clauses.
    private fun candidateBlocks(ix: IndexFile, lang: String, q: List<Clause>, exact: Boolean): Set<Int> {
        var acc: Set<Int>? = null
        for (c in q) {
            for (w in c) {
                val alts = alternatives(w, exact)
                val blocks = HashSet<Int>()
                for (a in alts) ix.blocks(lang, a).forEach { blocks += it }
                acc = acc?.intersect(blocks) ?: blocks
                if (acc.isEmpty()) return acc
            }
        }
        return acc.orEmpty()
    }
}
