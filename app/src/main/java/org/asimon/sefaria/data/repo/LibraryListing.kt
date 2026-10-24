package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.data.local.InstalledBook
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.local.ListingDao
import org.asimon.sefaria.data.local.OptionalFile
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.toAppError
import org.asimon.sefaria.data.datasource.CachedFetcher

/**
 * The server's listing of library files (/v1/library, SERVER_SPEC §7), stored in the listed_file
 * table so it is there offline, with the build it came from. The download queue is saved in the
 * same table (listed_file.queued), so a new listing keeps it.
 */
class LibraryListing(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val dao: ListingDao,
    scope: CoroutineScope,
) {
    private val lock = Mutex()

    /**
     * The server's downloadable books by title, as last listed; empty if never listed. Read from
     * the table while a screen shows downloads, and dropped a few seconds after the last one goes
     * (the whole listing is thousands of rows).
     */
    val available: StateFlow<Map<String, ListedFile>> =
        dao.listedBooks().map { l -> l.associateBy { it.book } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), emptyMap())

    /** What a first book download adds beyond the book: the forms table and dictionary, until downloaded. */
    val extra: StateFlow<Long> = dao.firstDownloadBytes().stateIn(scope, SharingStarted.Eagerly, 0L)

    /** The optional files listed (links, filters), with whether each is downloaded. */
    val optional: Flow<List<OptionalFile>> = dao.optionalFiles()

    /**
     * Brings the stored listing up to the server's build (asking with the stored build's ETag);
     * [force] fetches it whatever the build. Null when it is current, else why not: a stored
     * listing still serves.
     */
    suspend fun refresh(force: Boolean = false): AppError? = lock.withLock {
        if (fetcher.build == null) fetcher.refreshBuild()
        val build = fetcher.build
        val have = dao.build()
        if (!force && have != null && (build == null || have == build)) return@withLock null
        when (val r = api.library(have?.takeIf { !force }?.let { "\"$it\"" })) {
            is ApiResult.Ok -> {
                dao.putListing(r.value, r.value.buildId.ifEmpty { r.etag?.trim('"') ?: build.orEmpty() })
                null
            }
            ApiResult.NotModified -> {
                build?.let { dao.stampBuild(it) }
                null
            }
            is ApiResult.Failure -> r.error.toAppError()
        }
    }

    /** The listed books by title, now. */
    suspend fun books(): Map<String, ListedFile> = dao.listedBooks().first().associateBy { it.book }

    /** A listed book; null if the listing doesn't offer it. */
    suspend fun book(title: String): ListedFile? = dao.listedBook(title)

    /** Whether the stored listing offers [book]. */
    suspend fun isListed(book: String): Boolean = dao.listedBook(book) != null

    /** The listed dictionary, links file, forms table or filter file (a FileKind). */
    suspend fun shared(kind: String): ListedFile? = dao.listed(kind)

    /** Every file name the listing offers, index files included. */
    suspend fun names(): Set<String> = dao.listedNames().toSet()

    /** Downloaded books, in the file the listing has, that lack the index file it offers. */
    suspend fun missingIndexes(): List<ListedFile> = dao.missingIndexes()

    /** True if the server has another file of an installed book. */
    fun updateAvailable(p: InstalledBook, available: ListedFile?): Boolean =
        available != null && available.file != p.file

    /** Books queued for download, as last saved. */
    suspend fun savedQueue(): List<String> = dao.queuedBooks()

    /** Whether the last process left downloads to resume. */
    suspend fun hasSavedQueue(): Boolean = dao.hasQueued()

    /** Saves the queue: exactly [books] are queued. */
    suspend fun saveQueue(books: Collection<String>) = dao.setQueued(books)
}
