package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.data.local.FileKind
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.remote.Api

/**
 * The library files every downloaded book relies on (SERVER_SPEC §7): the dictionary book files are
 * compressed with, the links file and the Hebrew forms table, kept as current as the server's
 * listing. One of them downloads at a time.
 */
class SharedFiles(
    private val api: Api,
    private val listing: LibraryListing,
    private val library: Library,
    private val downloader: FileDownloader,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    @Volatile private var syncing: Job? = null

    /** Downloads and installs [dict] unless it is there; null when it is, else why not. */
    suspend fun ensureDict(dict: ListedFile): AppError? = lock.withLock {
        if (library.reader.hasDict(dict.sha256)) return@withLock null
        get(dict)?.let { return@withLock it }
        library.installer.installDict(library.files.partFor(dict.file), dict.sha256)?.let { AppError.BadResponse(it) }
    }

    /** Brings the links file up to the listing's, if one is listed. */
    suspend fun ensureLinks() = lock.withLock {
        val l = listing.shared(FileKind.LINKS) ?: return@withLock
        if (library.reader.linksSha() == l.sha256) return@withLock
        if (get(l) == null) library.installer.installLinks(library.files.partFor(l.file), l.file)
    }

    /** Brings the forms table up to the listing's, if one is listed. */
    suspend fun ensureForms() = lock.withLock {
        val f = listing.shared(FileKind.FORMS) ?: return@withLock
        if (library.reader.formsSha() == f.sha256) return@withLock
        if (get(f) == null) library.installer.installForms(library.files.partFor(f.file), f.sha256)
    }

    /**
     * Brings the downloaded library up to the server's listing, in the background: updates the
     * listing, drops partial downloads of files no longer listed, and, when books are downloaded,
     * updates the links file and forms table and fetches the index files that books downloaded
     * before them lack.
     */
    fun syncLibrary() {
        if (syncing?.isActive == true) return // every screen with downloads asks; one is enough
        syncing = scope.launch {
            library.reader.load()
            listing.refresh()
            library.files.dropParts(listing.names())
            if (!library.files.hasBooks()) return@launch
            ensureLinks()
            ensureForms()
            for (f in listing.missingIndexes()) {
                // a server without index files answers 404 for each: stop at the first
                if (fetchIndex(f) is AppError.NotFound) break
            }
        }
    }

    private suspend fun fetchIndex(f: ListedFile): AppError? {
        val ix = f.indexFile ?: return null
        val err = get(ix)
        if (err == null) library.installer.installIndex(f.book, library.files.partFor(ix.file), ix.file)
        return err
    }

    private suspend fun get(f: ListedFile): AppError? =
        downloader.download(api.libraryPath(f.file), f, library.files.partFor(f.file))
}
