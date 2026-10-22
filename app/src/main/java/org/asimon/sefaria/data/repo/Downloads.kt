package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Request
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.asimon.sefaria.data.local.FileKind
import org.asimon.sefaria.data.local.InstalledBook
import org.asimon.sefaria.data.local.LibraryDao
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.local.Synced
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** A download waiting for its turn, in progress, being verified, or the error that stopped it. */
sealed interface DownloadState {
    val active get() = this !is Failed

    data object Queued : DownloadState
    data class Running(val done: Long, val total: Long) : DownloadState
    /** Downloaded; waiting for, or in, the verify queue (checksum and install). */
    data object Verifying : DownloadState
    data class Failed(val error: AppError) : DownloadState
}

/**
 * Downloads a library file, resuming a partial one with a Range request ([fetch]), and verifies
 * its size and sha256, its name ([verify]). [download] does both.
 */
class FileDownloader(private val client: Call.Factory, private val baseUrl: suspend () -> String) {
    /** Downloads and verifies [part]; null on success. */
    suspend fun download(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? =
        fetch(path, f, part, progress) ?: verify(f, part)

    /** Downloads [part] without checking it; null once it has all its bytes. */
    suspend fun fetch(path: String, f: ListedFile, part: File, progress: (Long) -> Unit = {}): AppError? = withContext(Dispatchers.IO) {
        val url = (baseUrl().trimEnd('/') + path).toHttpUrlOrNull()
            ?: return@withContext AppError.BadResponse("bad URL $path")
        val what = f.book.ifEmpty { f.file }
        try {
            var have = if (part.length() <= f.bytes) part.length() else 0L.also { part.delete() }
            if (have < f.bytes) {
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        206 -> Unit
                        200 -> have = 0
                        else -> return@withContext if (resp.code == 404) AppError.NotFound(what)
                            else AppError.Server(resp.code, "HTTP ${resp.code}")
                    }
                    FileOutputStream(part, have > 0).use { out ->
                        val input = resp.body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var reported = System.nanoTime()
                        while (true) {
                            coroutineContext.ensureActive() // cancel keeps the partial file for a resume
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            // a few times a second: more only redraws the library list for nothing
                            if (System.nanoTime() - reported >= PROGRESS_NS) {
                                progress(have)
                                reported = System.nanoTime()
                            }
                        }
                    }
                }
            }
            progress(part.length())
            null
        } catch (e: IOException) {
            AppError.Network
        }
    }

    /** Checks [part]'s size and sha256; a mismatch deletes it. Null if it is good. */
    suspend fun verify(f: ListedFile, part: File): AppError? = withContext(Dispatchers.IO) {
        val ok = try {
            part.length() == f.bytes && sha256(part) == f.sha256
        } catch (e: IOException) {
            false
        }
        if (ok) return@withContext null
        part.delete()
        AppError.BadResponse("checksum mismatch for ${f.book.ifEmpty { f.file }}")
    }

    private companion object {
        const val PROGRESS_NS = 250_000_000L
    }

    private suspend fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive() // cancel keeps the whole file: the next try only verifies
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloaded books (SERVER_SPEC §7): the server's listing (stored in the listed_file table, so
 * it is there offline), downloads in the app scope with progress, and installed books. A book
 * download also fetches the book's index file (for offline search), the dictionary if it is
 * missing, and the links file and Hebrew forms table if they are missing or older than the
 * server's (they serve every downloaded book).
 *
 * A book goes through two queues: the download queue ([PARALLEL] at once) fetches its files,
 * then hands it to the verify queue ([VERIFY_PARALLEL] at once), which checks their checksums and
 * installs them. The two run side by side, so the next download starts while a book is verified.
 *
 * Downloads run in the app scope, and [onQueued] starts a worker (DownloadWorker) that keeps
 * the process alive while they do and shows their progress. The queue is saved in the listing
 * (listed_file.queued): if the system ends the process, WorkManager runs the worker again, which
 * resumes the queue ([resumePending]), each file from where it stopped.
 */
class DownloadRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val dao: LibraryDao,
    private val store: LibraryStore,
    private val downloader: FileDownloader,
    private val scope: CoroutineScope,
    private val onQueued: () -> Unit = {},
    parallel: Int = PARALLEL,
    verifiers: Int = VERIFY_PARALLEL,
) {
    val installed: StateFlow<Map<String, InstalledBook>> = store.installed.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    /** Disk used by the downloaded library. */
    val bytes: StateFlow<Long> = store.bytes.stateIn(scope, SharingStarted.Eagerly, 0L)
    /**
     * The server's downloadable books by title, as last listed; empty if never listed. Read from
     * the table while a screen shows downloads, and dropped a few seconds after the last one goes
     * (the whole listing is thousands of rows).
     */
    val available: StateFlow<Map<String, ListedFile>> =
        dao.listedBooks().map { l -> l.associateBy { it.book } }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), emptyMap())
    /** What a first book download adds beyond the book: the links file, forms table and dictionary, until downloaded. */
    val extra: StateFlow<Long> = dao.firstDownloadBytes().stateIn(scope, SharingStarted.Eagerly, 0L)
    // Persistent (structurally shared): an update copies O(log n) nodes, not the whole map, which
    // matters with the whole library queued and progress updating several times a second.
    private val _downloads = MutableStateFlow(persistentMapOf<String, DownloadState>())
    val downloads: StateFlow<PersistentMap<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val shared = Mutex() // dictionary and links file: one download at a time
    private val listing = Mutex()
    // Books download a few at a time, so a whole category or the whole library queues up
    // instead of opening thousands of connections.
    private val slots = Semaphore(parallel)
    // Verifying reads the whole file and hashes it: a couple at a time, apart from the downloads.
    private val verifySlots = Semaphore(verifiers)

    // books queued or downloading, or stopped by the network: saved, so a restart resumes them
    private val pending = MutableStateFlow(persistentSetOf<String>())

    init {
        scope.launch {
            // the latest queue at most once a second (waiting for a quiet moment never came
            // while a big download kept changing it)
            pending.drop(1).conflate().collect { p ->
                runCatching { dao.setQueued(p) }
                delay(1000)
            }
        }
    }

    /** Queues again what is pending: what the last process saved, and network failures. */
    suspend fun resumePending() {
        val titles = runCatching { dao.queuedBooks() }.getOrDefault(emptyList()) + pending.value
        if (titles.isEmpty()) return
        pending.update { it.addAll(titles) }
        downloadAll(titles)
    }

    /** Whether the last process left downloads to resume. */
    suspend fun hasSavedQueue(): Boolean = dao.hasQueued()

    /** Whether downloads are left to do (queued, running, or stopped by the network). */
    fun hasPending(): Boolean = pending.value.isNotEmpty()

    /** Waits until nothing is queued or running. */
    suspend fun awaitIdle() {
        downloads.first { m -> m.values.none { it.active } }
    }

    /**
     * Brings the stored listing up to the server's build (asking with the stored build's ETag);
     * [force] fetches it whatever the build. Null when it is current, else why not: a stored
     * listing still serves.
     */
    suspend fun refreshListing(force: Boolean = false): AppError? = listing.withLock {
        if (fetcher.build == null) fetcher.refreshBuild()
        val build = fetcher.build
        val have = dao.synced(Synced.LISTING)
        if (!force && have != null && (build == null || have == build)) return@withLock null
        when (val r = api.library(have?.takeIf { !force }?.let { "\"$it\"" })) {
            is ApiResult.Ok -> {
                dao.putListing(r.value, r.value.buildId.ifEmpty { r.etag?.trim('"') ?: build.orEmpty() })
                null
            }
            ApiResult.NotModified -> {
                build?.let { dao.stamp(Synced(Synced.LISTING, it)) }
                null
            }
            is ApiResult.Failure -> r.error.toAppError()
        }
    }

    suspend fun scan() = store.load()

    /**
     * Updates the listing, brings the links file and forms table up to date when books are
     * downloaded, and fetches the index files that books downloaded before them lack (in the
     * background).
     */
    fun refreshLinks() {
        if (refreshing?.isActive == true) return // every screen with downloads asks; one is enough
        refreshing = scope.launch {
            refreshListing()
            store.dropParts(dao.listedNames().toSet())
            if (!dao.hasBooks()) return@launch
            ensureLinks()
            ensureForms()
            for (f in dao.missingIndexes()) {
                // a server without index files answers 404 for each: stop at the first
                if (fetchIndex(f.book, f) is AppError.NotFound) break
            }
        }
    }

    @Volatile private var refreshing: Job? = null

    /** True if the server has another file of an installed book. */
    fun updateAvailable(p: InstalledBook, available: ListedFile?): Boolean =
        available != null && available.file != p.file

    /** Whether the stored listing offers [book]. */
    suspend fun isListed(book: String): Boolean = dao.listedBook(book) != null

    fun download(book: String) {
        if (jobs[book]?.isActive == true) return
        _downloads.update { it.put(book, DownloadState.Queued) }
        pending.update { it.add(book) }
        start(book)
        onQueued()
    }

    /** Runs a queued download (its state already set to [DownloadState.Queued]). */
    private fun start(book: String) {
        jobs[book] = scope.launch {
            var err = fetch(book)
            if (err is AppError.BadResponse || err is AppError.NotFound) {
                // the listing may be stale (stored before the server's build changed): refetch it once
                if (refreshListing(force = true) == null) err = fetch(book)
            }
            _downloads.update { if (err == null) it.remove(book) else it.put(book, DownloadState.Failed(err)) }
            // a network failure stays pending: the worker tries again when the network is back
            if (err != AppError.Network) pending.update { it.remove(book) }
            jobs.remove(book)
        }
    }

    /** Queues every book of [books] that the server has and that isn't downloaded and current. */
    suspend fun downloadAll(books: Collection<String>) {
        refreshListing()
        val available = dao.listedBooks().first().associateBy { it.book }
        if (available.isEmpty()) return // never listed: what is pending stays so
        val installed = store.installedNow()
        val queue = books.filter { b ->
            val f = available[b]
            f != null && installed[b]?.file != f.file && jobs[b]?.isActive != true
        }
        // One state update for the lot: one per book copied the growing map thousands of times.
        _downloads.update { m -> m.mutate { b -> queue.forEach { b[it] = DownloadState.Queued } } }
        val queued = queue.toHashSet() // a list here made this quadratic over the whole library
        pending.update { it.addAll(queue).removeAll(books.filter { b -> b !in queued && jobs[b]?.isActive != true }.toSet()) }
        queue.forEach(::start)
        if (queue.isNotEmpty()) onQueued()
    }

    /** Stops the downloads of [books], queued or running. */
    fun cancelAll(books: Collection<String>) {
        val set = books.toSet()
        set.forEach { jobs.remove(it)?.cancel() }
        _downloads.update { m -> m.mutate { b -> set.forEach(b::remove) } }
        pending.update { it.removeAll(set) }
    }

    /** A book's files, downloaded and not yet verified, or why not. */
    private sealed interface Fetched {
        class Files(val book: ListedFile, val index: ListedFile?) : Fetched
        class Error(val error: AppError) : Fetched
    }

    /** Takes [book] through the download queue, then the verify queue. */
    private suspend fun fetch(book: String): AppError? {
        val files = when (val r = slots.withPermit { downloadStage(book) }) {
            is Fetched.Error -> return r.error
            is Fetched.Files -> r
        }
        _downloads.update { if (book in it) it.put(book, DownloadState.Verifying) else it }
        return verifySlots.withPermit { verifyStage(book, files) }
    }

    /** The download stage: the dictionary if missing, then the book file and its index file, unchecked. */
    private suspend fun downloadStage(book: String): Fetched {
        val stale = refreshListing() // offline, the stored listing still serves
        val f = dao.listedBook(book) ?: return Fetched.Error(stale ?: AppError.NotFound(book))
        val dict = dao.listed(FileKind.DICT) ?: return Fetched.Error(stale ?: AppError.BadResponse("no dictionary listed"))
        _downloads.update { if (book in it) it.put(book, DownloadState.Running(0, f.bytes)) else it }
        shared.withLock {
            // the dictionary opens every book: it is verified and installed before any book is
            if (!store.hasDict(dict.sha256)) {
                get(dict)?.let { return Fetched.Error(it) }
                store.installDict(store.partFor(dict.file), dict.sha256)?.let { return Fetched.Error(AppError.BadResponse(it)) }
            }
        }
        val part = store.partFor(f.file)
        downloader.fetch(api.libraryPath(f.file), f, part) { done ->
            _downloads.update { if (book in it) it.put(book, DownloadState.Running(done, f.bytes)) else it }
        }?.let { return Fetched.Error(it) }
        // Failures from here on leave the book readable: offline search then scans it, and its
        // connections come from the server.
        val index = f.indexFile?.takeIf { ix -> downloader.fetch(api.libraryPath(ix.file), ix, store.partFor(ix.file)) == null }
        ensureLinks()
        ensureForms()
        return Fetched.Files(f, index)
    }

    /** The verify stage: checks the downloaded files and installs them. */
    private suspend fun verifyStage(book: String, files: Fetched.Files): AppError? {
        val part = store.partFor(files.book.file)
        downloader.verify(files.book, part)?.let { return it }
        store.installBook(book, part, files.book.file)?.let { return AppError.BadResponse(it) }
        files.index?.let { ix ->
            val p = store.partFor(ix.file)
            if (downloader.verify(ix, p) == null) store.installIndex(book, p, ix.file)
        }
        return null
    }

    private suspend fun fetchIndex(book: String, f: ListedFile): AppError? {
        val ix = f.indexFile ?: return null
        val err = get(ix)
        if (err == null) store.installIndex(book, store.partFor(ix.file), ix.file)
        return err
    }

    private suspend fun ensureForms() = shared.withLock {
        val f = dao.listed(FileKind.FORMS) ?: return@withLock
        if (store.formsSha() == f.sha256) return@withLock
        if (get(f) == null) store.installForms(store.partFor(f.file), f.sha256)
    }

    private suspend fun ensureLinks() = shared.withLock {
        val l = dao.listed(FileKind.LINKS) ?: return@withLock
        if (store.linksSha() == l.sha256) return@withLock
        if (get(l) == null) store.installLinks(store.partFor(l.file), l.file)
    }

    private suspend fun get(f: ListedFile, progress: (Long) -> Unit = {}): AppError? =
        downloader.download(api.libraryPath(f.file), f, store.partFor(f.file), progress)

    /** Stops a download; the partial file is kept so the next download resumes. */
    fun cancel(book: String) {
        jobs.remove(book)?.cancel()
        _downloads.update { it.remove(book) }
        pending.update { it.remove(book) }
    }

    suspend fun delete(book: String) {
        cancel(book)
        store.delete(book)
    }

    /** Deletes the downloads of [books]; null: every downloaded book. */
    suspend fun deleteAll(books: Collection<String>? = null) {
        val titles = books ?: store.installedNow().keys
        cancelAll(titles)
        store.deleteAll(titles)
    }

    companion object {
        /** Books downloading at once. */
        const val PARALLEL = 8
        /** Books being verified at once. */
        const val VERIFY_PARALLEL = 2
    }
}
