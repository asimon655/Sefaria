package org.asimon.sefaria.data.repo

import org.asimon.sefaria.core.text.Expansion
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.core.text.SearchText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.CompletionDto
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.core.text.TitleMatch

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
 * Title completion and full-text search (APP_SPEC §4.5, SERVER_SPEC §6.6). Full text asks the
 * server, which ranks; when the network fails it searches the downloaded books instead
 * (OfflineLibrary.search) in the server's library order: tier 1 (every word matched exactly),
 * then tier 2 (forms), each in search order (texts, then commentaries and targumim, each in TOC
 * order: LibraryDao.searchOrder, from the TOC's dependent flags), with the same forms table and matcher (SearchMatch). An offline cursor starts with
 * "o:" so paging stays on the side it started on.
 */
class SearchRepository(
    private val api: Api,
    private val library: LibraryRepository,
    private val store: LibraryStore,
    private val offline: OfflineLibrary,
) {
    /** Titles of the downloaded books, for the scope picker's shortcut. */
    val downloaded: Flow<Set<String>> = store.installed.map { it.keys }

    /**
     * Completions from the server (refs and titles); without the network, books whose names in the
     * TOC table match, typos forgiven (TitleMatch). Offline there are no refs: parsing one needs
     * the book's schema.
     */
    suspend fun complete(q: String): Load<List<CompletionDto>> = when (val r = api.complete(q)) {
        is ApiResult.Ok -> Load.Ok(r.value.items)
        is ApiResult.Failure -> if (r.error is ApiError.Network) completeOffline(q) else Load.Err(r.error.toAppError())
        ApiResult.NotModified -> Load.Ok(emptyList())
    }

    /** Books of the TOC table by name. The names are read for each query and not kept. */
    suspend fun completeOffline(q: String, limit: Int = COMPLETIONS): Load<List<CompletionDto>> {
        val names = library.bookNames()
        if (names.isEmpty()) return Load.Err(AppError.Network) // no TOC stored yet
        val best = withContext(Dispatchers.Default) {
            // texts before commentaries on a tie, then TOC order
            TitleMatch.rank(q, names.sortedBy { it.dependent }, limit) { listOf(it.title, it.he) }
        }
        return Load.Ok(best.map { CompletionDto(kind = "book", ref = "", title = it.title, he = it.he, book = it.title) }, offline = true)
    }

    /** What offline search keeps while the results page is open; null when none is. */
    private val sessionLock = Mutex()
    @Volatile private var session: SearchSession? = null

    /** The open session, or a new one if there is none or the library changed since. */
    private suspend fun session(): SearchSession = sessionLock.withLock {
        session?.takeIf { it.version == store.version } ?: SearchSession.open(store).also { session = it }
    }

    /** Lets go of what offline search opened (the results page closed). */
    fun release() {
        session = null
    }

    suspend fun page(q: String, filter: SearchFilter = SearchFilter(), cursor: String? = null, limit: Int = PAGE): Load<SearchPage> {
        if (cursor?.startsWith(OFFLINE) != true) {
            when (val r = api.search(q, filter, cursor, limit)) {
                is ApiResult.Ok -> return Load.Ok(SearchPage(r.value.lang, r.value.hits, r.value.next, offline = false, forms = r.value.forms))
                is ApiResult.Failure -> if (r.error !is ApiError.Network || cursor != null) return Load.Err(r.error.toAppError())
                ApiResult.NotModified -> return Load.Ok(SearchPage(filter.lang.orEmpty(), emptyList(), null, offline = false))
            }
        }
        return offline(q, filter, cursor?.removePrefix(OFFLINE), limit)
    }

    private suspend fun offline(q: String, filter: SearchFilter, cursor: String?, limit: Int): Load<SearchPage> = withContext(Dispatchers.Default) {
        offlinePage(q, filter, cursor, limit)
    }

    private suspend fun offlinePage(q: String, filter: SearchFilter, cursor: String?, limit: Int): Load<SearchPage> {
        val terms = SearchText.parse(q)
        val lang = filter.lang ?: if (SearchText.isHebrew(q)) "he" else "en"
        val s = session()
        val order = s.titles
        if (terms.isEmpty() || order.isEmpty()) return Load.Err(AppError.Network)
        val clauses = SearchMatch.expand(terms, store.forms(), Expansion(off = !filter.expand, all = filter.allForms, drop = filter.drop))
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
        tiers@ while (tier <= 2 && (tier == 1 || expanded)) {
            while (bi < books.size) {
                val title = books[bi]
                // books decoded for hits stay in the session, not in the reader's cache
                val book = suspend { s.decoded(title) { (library.book(title, remember = false) as? Load.Ok)?.value } }
                val found = offline.search(title, book, lang, clauses, tier, after, limit - hits.size, reader = s).orEmpty()
                hits += found.map { it.second }
                if (hits.size == limit) {
                    next = "$OFFLINE$tier:$bi:${found.last().first}"
                    break@tiers
                }
                bi++
                after = -1
            }
            tier++
            bi = 0
        }
        val forms = buildMap { for (c in clauses) for (w in c) if (w.forms.isNotEmpty()) put(w.text, w.forms.keys.sorted()) }
        return Load.Ok(SearchPage(lang, hits, next, offline = true, forms = forms))
    }

    /** Whether a book (with its TOC path, "Tanakh/Torah") is in the filter's scope: any of its books or categories. */
    private fun inScope(filter: SearchFilter, title: String, path: String): Boolean =
        !filter.scoped || title in filter.books || filter.categories.any { c -> path == c || path.startsWith("$c/") }

    companion object {
        const val PAGE = 20
        const val COMPLETIONS = 10

        /** A commentary or targum, as the server's search.IsDependent decides. */
        private const val OFFLINE = "o:"
    }
}
