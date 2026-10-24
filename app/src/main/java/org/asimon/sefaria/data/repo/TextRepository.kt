package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.VersionDto
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.SectionText
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.datasource.OfflineText
import org.asimon.sefaria.data.map

class TextRepository(private val api: Api, private val fetcher: CachedFetcher, private val offline: OfflineText? = null) {
    /** Loads exactly one section (the paging unit) in the book's default versions; a downloaded book's come from the library. */
    suspend fun section(section: Ref): Load<SectionText> {
        offline?.section(section)?.let { return Load.Ok(it) }
        val ref = section.url()
        return fetcher.get("text:$ref", ::decode, book = section.book.title) { etag -> api.text(ref, etag) }
            .map { t ->
                val s = t.sections.firstOrNull()
                SectionText(s?.ref ?: ref, s?.heRef ?: t.heRef, s?.first ?: 1, s?.he.orEmpty(), s?.en.orEmpty(), t.versions, s?.linkCounts.orEmpty())
            }
    }

    private suspend fun decode(json: String): TextDto =
        withContext(Dispatchers.Default) { SefariaJson.decodeFromString(TextDto.serializer(), json) }
}
