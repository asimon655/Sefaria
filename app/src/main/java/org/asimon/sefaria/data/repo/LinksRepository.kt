package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.datasource.OfflineLinks
import org.asimon.sefaria.data.map

/**
 * Connections (Phase 4). The downloaded links file answers first; otherwise they are cached
 * like texts, so connections of read segments work offline.
 */
class LinksRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val offline: OfflineLinks? = null,
    private val books: BookRepository? = null,
) {
    /** All connections of a segment of [source], grouped, without text. */
    suspend fun links(ref: String, source: String): Load<LinksDto> {
        local(ref, source) { offline?.links(it) }?.let { return Load.Ok(it) }
        return fetcher.get("links:$ref", ::decode) { etag -> api.links(ref, etag = etag) }
    }

    /** [book]'s connections of a segment of [source], with their text. */
    suspend fun withText(ref: String, source: String, book: String): Load<LinksDto> {
        local(ref, source) { offline?.links(it, only = book, withText = true) }?.let { return Load.Ok(it) }
        return fetcher.get("links:$ref|$book", ::decode) { etag -> api.links(ref, withText = true, book = book, etag = etag) }
    }

    private suspend fun local(ref: String, source: String, f: suspend (Ref) -> LinksDto?): LinksDto? {
        if (offline == null || books == null) return null
        val b = (books.book(source) as? Load.Ok)?.value ?: return null
        val r = Ref.parseOrNull(Catalog(listOf(b)), ref.replace('_', ' ')) ?: return null
        return f(r)
    }

    private suspend fun decode(json: String): LinksDto =
        withContext(Dispatchers.Default) { SefariaJson.decodeFromString(LinksDto.serializer(), json) }
}
