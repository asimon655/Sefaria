package org.asimon.sefaria.data.repo

import org.asimon.sefaria.core.text.Expansion
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.core.text.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.debugAssert
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import org.asimon.sefaria.data.local.InstalledDao
import org.asimon.sefaria.data.local.LibraryFiles
import org.asimon.sefaria.data.local.LibraryReader
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.toAppError
import org.asimon.sefaria.data.datasource.OfflineSearch
import org.asimon.sefaria.data.datasource.SearchSession

/**
 * One page of full-text hits. [offline] pages come from downloaded books only; [forms] are each
 * query word's forms that it also matched, shown as chips the user can turn off.
 */
data class SearchPage(
    val lang: String,
    val hits: List<SearchHitDto>,
    val next: String?,
    val offline: Boolean,
    val forms: Map<String, List<String>> = emptyMap(),
)

/**
 * Full-text search (APP_SPEC §4.5, SERVER_SPEC §6.6). It asks the server, which ranks; when the network fails it searches the downloaded books instead
 * (OfflineSearch) in the server's library order: tier 1 (every word matched exactly),
 * then tier 2 (forms), each in search order (texts, then commentaries and targumim, each in TOC
 * order: InstalledDao.searchBooks, from the TOC's dependent flags), with the same forms table and matcher (SearchMatch). An offline cursor starts with
 * "o:" so paging stays on the side it started on.
 */
class SearchRepository(
    private val api: Api,
    private val bookRepo: BookRepository,
    private val files: LibraryFiles,
    private val reader: LibraryReader,
    private val dao: InstalledDao,
    private val offline: OfflineSearch,
) {
    /** Titles of the downloaded books, for the scope picker's shortcut. */
    val downloaded: Flow<Set<String>> = files.installed.map { it.keys }

    /** What offline search keeps while the results page is open; null when none is. */
    private val sessionLock = Mutex()
    @Volatile private var session: SearchSession? = null

    /** The open session, or a new one if there is none or the library changed since. */
    private suspend fun session(): SearchSession = sessionLock.withLock {
        session?.takeIf { it.version == files.version } ?: SearchSession.open(files, reader, dao).also { session = it }
    }

    /** Lets go of what offline search opened (the results page closed). */
    fun release() {
        session = null
    }

    suspend fun page(q: String, filter: SearchFilter = SearchFilter(), cursor: String? = null, limit: Int = PAGE): Load<SearchPage> {
        var online: AppError? = null
        if (cursor?.startsWith(OFFLINE) != true) {
            when (val r = api.search(q, filter, cursor, limit)) {
                is ApiResult.Ok -> return Load.Ok(SearchPage(r.value.lang, r.value.hits, r.value.next, offline = false, forms = r.value.forms))
                is ApiResult.Failure -> {
                    // A first page the server can't give just now (no network, busy, too slow,
                    // failing) comes from the downloaded books instead. Later pages don't switch
                    // sides: the two order their hits differently.
                    val e = r.error.toAppError()
                    if (!e.transient || cursor != null) return Load.Err(e)
                    online = e
                }
                ApiResult.NotModified -> return Load.Ok(SearchPage(filter.lang.orEmpty(), emptyList(), null, offline = false))
            }
        }
        val r = offline(q, filter, cursor?.removePrefix(OFFLINE), limit)
        // nothing on the phone to search: the server's own error says more than "no network"
        return if (r is Load.Err && r.error == AppError.Network && online != null) Load.Err(online) else r
    }

    private suspend fun offline(q: String, filter: SearchFilter, cursor: String?, limit: Int): Load<SearchPage> = withContext(Dispatchers.Default) {
        offlinePage(q, filter, cursor, limit)
    }

    private suspend fun offlinePage(q: String, filter: SearchFilter, cursor: String?, limit: Int): Load<SearchPage> {
        val terms = SearchText.parse(q)
        // word* has no index path offline: every block of every book would be read
        if (terms.any { it.prefix }) return Load.Err(AppError.PrefixOffline)
        val lang = filter.lang ?: if (SearchText.isHebrew(q)) "he" else "en"
        val s = session()
        val order = s.titles
        if (terms.isEmpty() || order.isEmpty()) return Load.Err(AppError.Network)
        val clauses = SearchMatch.expand(terms, reader.forms(), Expansion(off = !filter.expand, all = filter.allForms, drop = filter.drop))
        val books = if (!filter.scoped) order else {
            val paths = s.paths()
            order.filter { inScope(filter, it, paths[it].orEmpty()) }
        }
        val expanded = clauses.any { c -> c.any { it.forms.isNotEmpty() } }
        // cursor: "<tier>:<position in books>:<slot>"
        val parts = cursor?.split(':')
        var tier = parts?.getOrNull(0)?.toIntOrNull() ?: 1
        var bi = parts?.getOrNull(1)?.toIntOrNull() ?: 0
        var after = parts?.getOrNull(2)?.toIntOrNull() ?: -1
        val hits = mutableListOf<SearchHitDto>()
        var next: String? = null
        // Books are searched several at a time, on every core, and their hits taken in book
        // order, so the page is the one a book-by-book search gives. A run of books with nothing
        // (a rare word: most are ruled out by their filter or index) widens the batch; a hit
        // narrows it again, since a batch searched past the end of the page is work thrown away.
        tiers@ while (tier <= 2 && (tier == 1 || expanded)) {
            var width = BATCH_MIN
            while (bi < books.size) {
                val batch = books.subList(bi, minOf(bi + width, books.size))
                val need = limit - hits.size
                val firstAfter = after
                val found = coroutineScope {
                    batch.mapIndexed { i, title ->
                        async {
                            // books decoded for hits stay in the session, not in the reader's cache
                            val book = suspend { s.decoded(title) { (bookRepo.book(title, remember = false) as? Load.Ok)?.value } }
                            offline.search(title, book, lang, clauses, tier, if (i == 0) firstAfter else -1, need, reader = s).orEmpty()
                        }
                    }.awaitAll()
                }
                for ((i, f) in found.withIndex()) {
                    debugAssert(f.zipWithNext().all { (a, b) -> a.first < b.first }) { "hits of ${batch[i]} are not in reading order" }
                    val take = f.take(limit - hits.size)
                    hits += take.map { it.second }
                    if (hits.size == limit) {
                        next = "$OFFLINE$tier:${bi + i}:${take.last().first}"
                        break@tiers
                    }
                }
                bi += batch.size
                after = -1
                width = if (found.all { it.isEmpty() }) minOf(width * 2, BATCH_MAX) else BATCH_MIN
            }
            tier++
            bi = 0
        }
        debugAssert(hits.size <= limit && (next == null || hits.size == limit)) { "an offline page of ${hits.size} hits for a limit of $limit, next $next" }
        val forms = buildMap { for (c in clauses) for (w in c) if (w.forms.isNotEmpty()) put(w.text, w.forms.keys.sorted()) }
        return Load.Ok(SearchPage(lang, hits, next, offline = true, forms = forms))
    }

    /** Whether a book (with its TOC path, "Tanakh/Torah") is in the filter's scope: any of its books or categories. */
    private fun inScope(filter: SearchFilter, title: String, path: String): Boolean =
        !filter.scoped || title in filter.books || filter.categories.any { c -> path == c || path.startsWith("$c/") }

    companion object {
        const val PAGE = 20

        /** Books searched at once offline: from one per core, up to [BATCH_MAX] while nothing is found. */
        private val BATCH_MIN = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        private const val BATCH_MAX = 256

        /** How an offline cursor starts. */
        private const val OFFLINE = "o:"
    }
}
