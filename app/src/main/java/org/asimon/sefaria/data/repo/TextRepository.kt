package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.remote.VersionDto

/** One section of text in both languages; segment i has number first + i. */
data class SectionText(
    val ref: String,
    val heRef: String,
    val first: Int,
    val he: List<String>,
    val en: List<String>,
    val versions: Map<String, VersionDto>,
    val linkCounts: List<Int> = emptyList(),
) {
    val size get() = maxOf(he.size, en.size)
}

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
