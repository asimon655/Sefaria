package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.data.AppError
import org.asimon.sefaria.data.datasource.FileDownloader
import org.asimon.sefaria.data.local.FileKind
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.remote.Api

/**
 * An optional library file ([FileKind.LINKS] or [FileKind.FILTERS]) as the settings and the
 * download dialogs show it: its size, whether it is downloaded, the bytes done while it
 * downloads ([progress]), the bytes on disk of a download stopped midway ([partial]), and why
 * its last download failed.
 */
data class OptionalStatus(
    val kind: String,
    val bytes: Long,
    val installed: Boolean,
    val progress: Long? = null,
    val error: AppError? = null,
    val partial: Long = 0,
) {
    val downloading get() = progress != null
    val paused get() = !installed && !downloading && partial > 0
}

/**
 * The library files beyond the books (SERVER_SPEC §7), kept as current as the server's listing:
 * the dictionary book files are compressed with and the Hebrew forms table, which the first book
 * download brings, and the optional links file and word filter file, which download only when
 * asked ([downloadOptional]) and are then updated with the library. Each file downloads one at
 * a time; different files download side by side.
 */
class SharedFiles(
    private val api: Api,
    private val listing: LibraryListing,
    private val library: Library,
    private val downloader: FileDownloader,
    private val scope: CoroutineScope,
) {
    private val locks = listOf(FileKind.DICT, FileKind.FORMS, FileKind.LINKS, FileKind.FILTERS).associateWith { Mutex() }
    private val running = MutableStateFlow<Map<String, Long>>(emptyMap()) // kind -> bytes done
    private val failed = MutableStateFlow<Map<String, AppError>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>() // optional downloads asked for, by kind
    @Volatile private var syncing: Job? = null

    /** The optional files the listing offers, links first; a stopped download's .part size is read from disk. */
    val optional: Flow<List<OptionalStatus>> = combine(listing.optional, running, failed) { files, r, e ->
        files.map {
            val partial = if (it.installed || it.kind in r) 0L else library.files.partFor(it.file).length()
            OptionalStatus(it.kind, it.bytes, it.installed, r[it.kind], e[it.kind], partial)
        }
    }.flowOn(Dispatchers.IO)

    /** Downloads and installs [dict] unless it is there; null when it is, else why not. */
    suspend fun ensureDict(dict: ListedFile): AppError? = locks.getValue(FileKind.DICT).withLock {
        if (library.reader.hasDict(dict.sha256)) return@withLock null
        get(dict)?.let { return@withLock it }
        library.installer.installDict(library.files.partFor(dict.file), dict.sha256)?.let { AppError.BadResponse(it) }
    }

    /** Brings the forms table up to the listing's, if one is listed. */
    suspend fun ensureForms() = locks.getValue(FileKind.FORMS).withLock {
        val f = listing.shared(FileKind.FORMS) ?: return@withLock
        if (library.reader.formsSha() == f.sha256) return@withLock
        if (get(f) == null) library.installer.installForms(library.files.partFor(f.file), f.sha256)
    }

    /** Downloads the optional file of [kind] in the background (resuming a stopped one), unless it is downloading. */
    fun downloadOptional(kind: String) {
        if (kind in running.value || jobs[kind]?.isActive == true) return
        failed.update { it - kind }
        jobs[kind] = scope.launch { fetchOptional(kind)?.let { e -> failed.update { it + (kind to e) } } }
    }

    /** Stops (pauses) the download of the optional file of [kind]; its .part stays for the next one. */
    fun pauseOptional(kind: String) {
        jobs.remove(kind)?.cancel()
    }

    /** Deletes the downloaded optional file of [kind]. */
    suspend fun deleteOptional(kind: String) {
        failed.update { it - kind }
        library.installer.deleteOptional(kind)
    }

    /**
     * Brings the downloaded library up to the server's listing, in the background: updates the
     * listing, drops partial downloads of files no longer listed, updates the optional files that
     * are downloaded and, when books are downloaded, the forms table, and fetches the index files
     * that books downloaded before them lack.
     */
    fun syncLibrary() {
        if (syncing?.isActive == true) return // every screen with downloads asks; one is enough
        syncing = scope.launch {
            library.reader.load()
            listing.refresh()
            library.files.dropParts(listing.names())
            if (library.reader.linksSha() != null) fetchOptional(FileKind.LINKS)
            if (library.reader.filtersSha() != null) fetchOptional(FileKind.FILTERS)
            if (!library.files.hasBooks()) return@launch
            ensureForms()
            for (f in listing.missingIndexes()) {
                // a server without index files answers 404 for each: stop at the first
                if (fetchIndex(f) is AppError.NotFound) break
            }
        }
    }

    // Brings the optional file of [kind] up to the listing's; null when it is (or none is listed), else why not.
    private suspend fun fetchOptional(kind: String): AppError? = locks.getValue(kind).withLock {
        val l = listing.shared(kind) ?: return@withLock null
        val have = if (kind == FileKind.LINKS) library.reader.linksSha() else library.reader.filtersSha()
        if (have == l.sha256) return@withLock null
        running.update { it + (kind to 0L) }
        try {
            get(l) { done -> running.update { it + (kind to done) } }?.let { return@withLock it }
            val part = library.files.partFor(l.file)
            val err = if (kind == FileKind.LINKS) library.installer.installLinks(part, l.file) else library.installer.installFilters(part, l.file)
            err?.let { AppError.BadResponse(it) }
        } finally {
            running.update { it - kind }
        }
    }

    private suspend fun fetchIndex(f: ListedFile): AppError? {
        val ix = f.indexFile ?: return null
        val err = get(ix)
        if (err == null) library.installer.installIndex(f.book, library.files.partFor(ix.file), ix.file)
        return err
    }

    private suspend fun get(f: ListedFile, progress: (Long) -> Unit = {}): AppError? =
        downloader.download(api.libraryPath(f.file), f, library.files.partFor(f.file), progress)
}
