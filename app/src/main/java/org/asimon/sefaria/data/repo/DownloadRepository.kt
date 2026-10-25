package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.asimon.sefaria.data.local.FileKind
import org.asimon.sefaria.data.local.InstalledBook
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.remote.Api
import java.util.concurrent.ConcurrentHashMap
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.datasource.FileDownloader

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
 * Book downloads (SERVER_SPEC §7), in the app scope with progress, and the downloaded books. A
 * book download fetches the book's file and index file (for offline search) as [listing] lists
 * them, and brings the [shared] files every book needs (dictionary, forms table) up to date; the
 * optional links and filter files download only when asked (SharedFiles.downloadOptional).
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
    private val listing: LibraryListing,
    private val shared: SharedFiles,
    private val library: Library,
    private val downloader: FileDownloader,
    private val scope: CoroutineScope,
    private val onQueued: () -> Unit = {},
    parallel: Int = PARALLEL,
    verifiers: Int = VERIFY_PARALLEL,
) {
    val installed: StateFlow<Map<String, InstalledBook>> = library.files.installed.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    /** Disk used by the downloaded library. */
    val bytes: StateFlow<Long> = library.files.bytes.stateIn(scope, SharingStarted.Eagerly, 0L)
    // Persistent (structurally shared): an update copies O(log n) nodes, not the whole map, which
    // matters with the whole library queued and progress updating several times a second.
    private val _downloads = MutableStateFlow(persistentMapOf<String, DownloadState>())
    val downloads: StateFlow<PersistentMap<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    // Books download a few at a time, so a whole category or the whole library queues up
    // instead of opening thousands of connections.
    private val slots = Semaphore(parallel)
    // Verifying reads the whole file and hashes it: a couple at a time, apart from the downloads.
    private val verifySlots = Semaphore(verifiers)

    // books queued or downloading, or stopped by the network: saved, so a restart resumes them
    private val pending = MutableStateFlow(persistentSetOf<String>())

    private val _paused = MutableStateFlow(persistentMapOf<String, Long>())
    /**
     * Books whose download was stopped midway, with the bytes of their book file already on disk
     * (its .part file): the next download resumes from there. Filled by [findPaused] and by each stop.
     */
    val paused: StateFlow<PersistentMap<String, Long>> = _paused.asStateFlow()
    @Volatile private var scanned = false

    init {
        scope.launch {
            // the latest queue at most once a second (waiting for a quiet moment never came
            // while a big download kept changing it)
            pending.drop(1).conflate().collect { p ->
                runCatching { listing.saveQueue(p) }
                delay(1000)
            }
        }
    }

    /** Queues again what is pending: what the last process saved, and network failures. */
    suspend fun resumePending() {
        val titles = runCatching { listing.savedQueue() }.getOrDefault(emptyList()) + pending.value
        if (titles.isEmpty()) return
        pending.update { it.addAll(titles) }
        downloadAll(titles)
    }

    /** Whether the last process left downloads to resume. */
    suspend fun hasSavedQueue(): Boolean = listing.hasSavedQueue()

    /** Whether downloads are left to do (queued, running, or stopped by the network). */
    fun hasPending(): Boolean = pending.value.isNotEmpty()

    /** Waits until nothing is queued or running. */
    suspend fun awaitIdle() {
        downloads.first { m -> m.values.none { it.active } }
    }

    /**
     * Finds the downloads a last run left midway, from the .part files, once: not at start, since
     * the library folder can hold thousands of files.
     */
    suspend fun findPaused() {
        if (scanned) return
        scanned = true
        val parts = withContext(Dispatchers.IO) {
            library.files.dir.listFiles { f -> f.name.endsWith(".sfb.part") }.orEmpty()
                .associate { it.name.removeSuffix(".part") to it.length() }.filterValues { it > 0 }
        }
        if (parts.isEmpty()) return
        val byFile = listing.books().values.associateBy { it.file }
        _paused.update { m ->
            m.mutate { b ->
                for ((file, n) in parts) {
                    val title = byFile[file]?.book ?: continue
                    if (jobs[title]?.isActive != true && title !in _downloads.value) b[title] = n
                }
            }
        }
    }

    // After [stopped] end, records which of [books] have part of their book file on disk.
    private fun notePaused(books: Collection<String>, stopped: List<Job>) {
        if (books.isEmpty()) return
        scope.launch {
            stopped.joinAll()
            val listed = if (books.size == 1) listOfNotNull(listing.book(books.first())).associateBy { it.book } else listing.books()
            val sizes = withContext(Dispatchers.IO) {
                books.associateWith { t -> listed[t]?.let { library.files.partFor(it.file).length() } ?: 0L }
            }
            _paused.update { m -> m.mutate { b -> for ((t, n) in sizes) if (n > 0 && t !in _downloads.value) b[t] = n else b.remove(t) } }
        }
    }

    fun download(book: String) {
        if (jobs[book]?.isActive == true) return
        _paused.update { it.remove(book) }
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
                if (listing.refresh(force = true) == null) err = fetch(book)
            }
            _downloads.update { if (err == null) it.remove(book) else it.put(book, DownloadState.Failed(err)) }
            // a network failure stays pending: the worker tries again when the network is back
            if (err != AppError.Network) pending.update { it.remove(book) }
            jobs.remove(book)
        }
    }

    /** Queues every book of [books] that the server has and that isn't downloaded and current. */
    suspend fun downloadAll(books: Collection<String>) {
        listing.refresh()
        val available = listing.books()
        if (available.isEmpty()) return // never listed: what is pending stays so
        val installed = library.files.installedNow()
        val queue = books.filter { b ->
            val f = available[b]
            f != null && installed[b]?.file != f.file && jobs[b]?.isActive != true
        }
        // One state update for the lot: one per book copied the growing map thousands of times.
        _downloads.update { m -> m.mutate { b -> queue.forEach { b[it] = DownloadState.Queued } } }
        _paused.update { m -> m.mutate { b -> queue.forEach(b::remove) } }
        val queued = queue.toHashSet() // a list here made this quadratic over the whole library
        pending.update { it.addAll(queue).removeAll(books.filter { b -> b !in queued && jobs[b]?.isActive != true }.toSet()) }
        queue.forEach(::start)
        if (queue.isNotEmpty()) onQueued()
    }

    /** Stops (pauses) the downloads of [books], queued or running; their partial files are kept. */
    fun cancelAll(books: Collection<String>) {
        val set = books.toSet()
        val stopped = set.mapNotNull { t -> jobs.remove(t)?.also { it.cancel() }?.let { t to it } }
        _downloads.update { m -> m.mutate { b -> set.forEach(b::remove) } }
        pending.update { it.removeAll(set) }
        notePaused(stopped.map { it.first }, stopped.map { it.second })
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
        val stale = listing.refresh() // offline, the stored listing still serves
        val f = listing.book(book) ?: return Fetched.Error(stale ?: AppError.NotFound(book))
        val dict = listing.shared(FileKind.DICT) ?: return Fetched.Error(stale ?: AppError.BadResponse("no dictionary listed"))
        _downloads.update { if (book in it) it.put(book, DownloadState.Running(0, f.bytes)) else it }
        // the dictionary opens every book: it is verified and installed before any book is
        shared.ensureDict(dict)?.let { return Fetched.Error(it) }
        val part = library.files.partFor(f.file)
        downloader.fetch(api.libraryPath(f.file), f, part) { done ->
            _downloads.update { if (book in it) it.put(book, DownloadState.Running(done, f.bytes)) else it }
        }?.let { return Fetched.Error(it) }
        // Failures from here on leave the book readable: offline search then scans it, and its
        // connections come from the server.
        val index = f.indexFile?.takeIf { ix -> downloader.fetch(api.libraryPath(ix.file), ix, library.files.partFor(ix.file)) == null }
        shared.ensureForms()
        return Fetched.Files(f, index)
    }

    /** The verify stage: checks the downloaded files and installs them. */
    private suspend fun verifyStage(book: String, files: Fetched.Files): AppError? {
        val part = library.files.partFor(files.book.file)
        downloader.verify(files.book, part)?.let { return it }
        library.installer.installBook(book, part, files.book.file)?.let { return AppError.BadResponse(it) }
        files.index?.let { ix ->
            val p = library.files.partFor(ix.file)
            if (downloader.verify(ix, p) == null) library.installer.installIndex(book, p, ix.file)
        }
        return null
    }

    /** Stops (pauses) a download; the partial file is kept so the next download resumes. */
    fun cancel(book: String) {
        val job = jobs.remove(book)?.also { it.cancel() }
        _downloads.update { it.remove(book) }
        pending.update { it.remove(book) }
        if (job != null) notePaused(listOf(book), listOf(job))
    }

    suspend fun delete(book: String) {
        cancel(book)
        library.installer.delete(book)
    }

    /** Deletes the downloads of [books]; null: everything downloaded, the optional files too. */
    suspend fun deleteAll(books: Collection<String>? = null) {
        val titles = books ?: library.files.installedNow().keys
        cancelAll(titles)
        library.installer.deleteAll(titles)
        if (books == null) for (kind in listOf(FileKind.LINKS, FileKind.FILTERS)) shared.deleteOptional(kind)
    }

    companion object {
        /** Books downloading at once. */
        const val PARALLEL = 8
        /** Books being verified at once. */
        const val VERIFY_PARALLEL = 2
    }
}
