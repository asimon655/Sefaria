package org.asimon.sefaria.data.repo

import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.local.PackStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.CompletionDto
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.TocNodeDto
import org.asimon.sefaria.data.remote.VersionSelection

/** One page of full-text hits. [offline] pages come from downloaded books only. */
data class SearchPage(val lang: String, val hits: List<SearchHitDto>, val next: String?, val offline: Boolean)

/**
 * Title completion and full-text search (APP_SPEC §4.5). Full text asks the server; when the
 * network fails it searches the downloaded books instead, in the server's order (texts, then
 * commentaries and targumim, each in TOC order), with the same normaliser and query syntax
 * (SearchText). An offline cursor starts with "o:" so paging stays
 * on the side it started on.
 */
class SearchRepository(
    private val api: Api,
    private val library: LibraryRepository,
    private val packs: PackStore,
) {
    suspend fun complete(q: String): Load<List<CompletionDto>> = when (val r = api.complete(q)) {
        is ApiResult.Ok -> Load.Ok(r.value.items)
        is ApiResult.Failure -> Load.Err(r.error.toAppError())
        ApiResult.NotModified -> Load.Ok(emptyList())
    }

    suspend fun page(q: String, filter: SearchFilter = SearchFilter(), cursor: String? = null, limit: Int = PAGE): Load<SearchPage> {
        if (cursor?.startsWith(OFFLINE) != true) {
            when (val r = api.search(q, filter, cursor, limit)) {
                is ApiResult.Ok -> return Load.Ok(SearchPage(r.value.lang, r.value.hits, r.value.next, offline = false))
                is ApiResult.Failure -> if (r.error !is ApiError.Network || cursor != null) return Load.Err(r.error.toAppError())
                ApiResult.NotModified -> return Load.Ok(SearchPage(filter.lang.orEmpty(), emptyList(), null, offline = false))
            }
        }
        return offline(q, filter, cursor?.removePrefix(OFFLINE), limit)
    }

    private suspend fun offline(q: String, filter: SearchFilter, cursor: String?, limit: Int): Load<SearchPage> {
        val terms = SearchText.parse(q)
        val lang = filter.lang ?: if (SearchText.isHebrew(q)) "he" else "en"
        val installed = packs.installed.value.keys
        if (terms.isEmpty() || installed.isEmpty()) return Load.Err(AppError.Network)
        val books = tocOrder().filter { (title, path) ->
            title in installed && (filter.book == null || filter.book == title) &&
                (filter.category == null || path == filter.category || path.startsWith(filter.category + "/"))
        }.map { it.first }.let { titles ->
            val tier = titles.associateWith { t -> (library.book(t) as? Load.Ok)?.value?.dto?.let(::isDependent) == true }
            titles.sortedBy { tier[it] } // stable: TOC order within each tier
        }
        // cursor: "<position in books>:<slot>"
        var bi = cursor?.substringBefore(':')?.toIntOrNull() ?: 0
        var after = cursor?.substringAfter(':')?.toIntOrNull() ?: -1
        val expr = SearchText.expr(terms)
        val hits = mutableListOf<SearchHitDto>()
        var next: String? = null
        while (bi < books.size) {
            val found = packs.search(books[bi], lang, expr, after, limit - hits.size)
            for (h in found) {
                hits += SearchHitDto(h.ref, h.human, h.heRef, h.book, SearchText.snippet(segment(h.book, h.section, h.i, lang), terms))
            }
            if (hits.size == limit) {
                next = OFFLINE + bi + ":" + found.last().slot
                break
            }
            bi++
            after = -1
        }
        return Load.Ok(SearchPage(lang, hits, next, offline = true))
    }

    private suspend fun segment(book: String, section: String, i: Int, lang: String): String {
        val json = packs.get(book, "text:$section|${VersionSelection().key}") ?: return ""
        val sec = SefariaJson.decodeFromString(TextDto.serializer(), json).sections.firstOrNull() ?: return ""
        return (if (lang == "he") sec.he else sec.en).getOrNull(i).orEmpty()
    }

    /** Books in TOC order with their category paths ("Tanakh/Torah"); downloaded books missing from the TOC go last. */
    private suspend fun tocOrder(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun walk(nodes: List<TocNodeDto>, path: String) {
            for (n in nodes) {
                if (n.isBook) out += n.title to path
                else walk(n.children, if (path.isEmpty()) n.en else path + "/" + n.en)
            }
        }
        (library.toc() as? Load.Ok)?.let { walk(it.value.tree, "") }
        val seen = out.map { it.first }.toSet()
        return out + packs.installed.value.keys.filter { it !in seen }.sorted().map { it to "" }
    }

    companion object {
        const val PAGE = 20

        /** A commentary or targum, as the server's search.IsDependent decides. */
        fun isDependent(b: BookDto) = b.dependence.isNotEmpty() ||
            b.categories.any { "Commentary" in it || "Targum" in it || " on " in it }
        private const val OFFLINE = "o:"
    }
}
