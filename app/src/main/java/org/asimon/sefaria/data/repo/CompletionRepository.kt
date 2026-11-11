package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.debugAssert
import org.asimon.sefaria.core.text.TitleMatch
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.local.BookName
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.CompletionDto
import org.asimon.sefaria.data.toAppError

/**
 * Completion for the search box (APP_SPEC §4.5). Two sources: the books of the stored TOC by
 * name, matched on the phone ([local]: there at once, and all there is offline), and the
 * server's list ([complete]: the query as a ref, books, places in books).
 */
class CompletionRepository(private val api: Api, private val toc: TocRepository) {
    /** The TOC's book names, folded for matching once; kept while the stored tree is of [build]. */
    private class Names(val build: String?, val books: List<BookName>, val folded: List<List<TitleMatch.Name>>)

    @Volatile private var names: Names? = null
    private val namesLock = Mutex()

    /** What the server answered, by query: typing a letter and deleting it asks once. Guarded by itself. */
    private val answers = object : LinkedHashMap<String, List<CompletionDto>>(ANSWERS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CompletionDto>>) = size > ANSWERS
    }
    private var answersBuild: String? = null // the build [answers] came from: a new library forgets them

    /** The server's answer to [q] if it was asked before; null otherwise. Doesn't touch the network. */
    fun answered(q: String): List<CompletionDto>? = synchronized(answers) {
        if (answersBuild != toc.storedBuild) null else answers[q]
    }

    /**
     * Completions from the server (refs, titles, places in books); without the network, the
     * books whose names in the TOC match ([local]). Offline there are no refs: parsing one
     * needs the book's schema.
     */
    suspend fun complete(q: String): Load<List<CompletionDto>> {
        answered(q)?.let { return Load.Ok(it) }
        val build = toc.storedBuild
        return when (val r = api.complete(q)) {
            is ApiResult.Ok -> {
                synchronized(answers) {
                    if (answersBuild != build) {
                        answers.clear()
                        answersBuild = build
                    }
                    answers[q] = r.value.items
                    debugAssert(answers.size <= ANSWERS) { "${answers.size} completion answers kept, over $ANSWERS" }
                }
                Load.Ok(r.value.items)
            }
            is ApiResult.Failure -> r.error.toAppError().let { e -> if (e.transient) completeOffline(q) else Load.Err(e) }
            ApiResult.NotModified -> Load.Ok(emptyList())
        }
    }

    /**
     * [q] as a place to open, if the server reads it as a ref below a whole book ("Ber 2a",
     * "בראשית א"); null for anything else, a bare title included, and when the server can't be asked.
     */
    suspend fun ref(q: String): CompletionDto? = when (val r = api.resolve(q)) {
        is ApiResult.Ok -> r.value.takeIf { it.kind != "book" }?.let { CompletionDto(kind = "ref", ref = it.ref, title = it.human, he = it.heRef, book = it.book) }
        else -> null
    }

    /**
     * Books of the stored TOC whose names match [q], typos forgiven (TitleMatch): no network, a
     * few milliseconds, so the list can follow every letter. Empty when no TOC is stored yet.
     */
    suspend fun local(q: String, limit: Int = COMPLETIONS): List<CompletionDto> {
        val n = names()
        if (n.books.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) { TitleMatch.rank(q, n.books, n.folded, limit) }
            .map { CompletionDto(kind = "book", ref = "", title = it.title, he = it.he, book = it.title) }
    }

    /**
     * Reads and folds the book names before the first letter is typed (a few thousand names:
     * tens of milliseconds on a phone), so that letter's matches don't wait for it.
     */
    suspend fun warm() {
        names()
    }

    /** [local] as the answer when the server can't be reached. */
    suspend fun completeOffline(q: String, limit: Int = COMPLETIONS): Load<List<CompletionDto>> {
        if (names().books.isEmpty()) return Load.Err(AppError.Network) // no TOC stored yet
        return Load.Ok(local(q, limit), offline = true)
    }

    private suspend fun names(): Names {
        names?.takeIf { it.build == toc.storedBuild && it.books.isNotEmpty() }?.let { return it }
        return namesLock.withLock {
            names?.takeIf { it.build == toc.storedBuild && it.books.isNotEmpty() }?.let { return it }
            // texts before commentaries on a tie, then TOC order
            val books = toc.bookNames().sortedBy { it.dependent }
            val folded = withContext(Dispatchers.Default) { books.map { TitleMatch.fold(listOf(it.title, it.he)) } }
            debugAssert(books.size == folded.size) { "${books.size} books with ${folded.size} folded names" }
            Names(toc.storedBuild, books, folded).also { names = it }
        }
    }

    companion object {
        const val COMPLETIONS = 10

        /** How many of the server's answers are kept. */
        const val ANSWERS = 64
    }
}
