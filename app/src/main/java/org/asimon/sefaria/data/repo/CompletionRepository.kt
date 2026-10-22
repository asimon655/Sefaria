package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.text.TitleMatch
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.CompletionDto

/** Title completion for the search box (APP_SPEC §4.5): the server's, or the TOC's names offline. */
class CompletionRepository(private val api: Api, private val toc: TocRepository) {
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
        val names = toc.bookNames()
        if (names.isEmpty()) return Load.Err(AppError.Network) // no TOC stored yet
        val best = withContext(Dispatchers.Default) {
            // texts before commentaries on a tie, then TOC order
            TitleMatch.rank(q, names.sortedBy { it.dependent }, limit) { listOf(it.title, it.he) }
        }
        return Load.Ok(best.map { CompletionDto(kind = "book", ref = "", title = it.title, he = it.he, book = it.title) }, offline = true)
    }

    companion object {
        const val COMPLETIONS = 10
    }
}
