package org.asimon.sefaria.data.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.asimon.sefaria.data.local.InstalledBook
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto
import org.asimon.sefaria.data.remote.SefariaJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** A download in progress, or the error that stopped it. */
sealed interface DownloadState {
    data class Running(val done: Long, val total: Long) : DownloadState
    data class Failed(val error: AppError) : DownloadState
}

/**
 * Downloads a library file, resuming a partial one with a Range request, and verifies its
 * size and sha256 (its name). Returns null on success; a failed checksum deletes the partial file.
 */
class FileDownloader(private val client: OkHttpClient, private val baseUrl: suspend () -> String) {
    suspend fun download(path: String, f: LibraryFileDto, part: File, progress: (Long) -> Unit = {}): AppError? = withContext(Dispatchers.IO) {
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
                        var reported = have
                        while (true) {
                            coroutineContext.ensureActive() // cancel keeps the partial file for a resume
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - reported >= 256 * 1024) {
                                progress(have)
                                reported = have
                            }
                        }
                    }
                }
            }
            if (part.length() != f.bytes || sha256(part) != f.sha256) {
                part.delete()
                return@withContext AppError.BadResponse("checksum mismatch for $what")
            }
            progress(f.bytes)
            null
        } catch (e: IOException) {
            AppError.Network
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloaded books (SERVER_SPEC §7a): the server's library listing, downloads in the app scope
 * with progress, and installed books. A book download also fetches the dictionary if it is
 * missing and the links file if it is missing or older than the server's (it serves every
 * downloaded book's connections). KISS: downloads run in the app process instead of
 * WorkManager; a cancelled or interrupted download resumes where it stopped.
 */
class DownloadRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val store: LibraryStore,
    private val downloader: FileDownloader,
    private val scope: CoroutineScope,
) {
    val installed: StateFlow<Map<String, InstalledBook>> = store.installed
    /** Disk used by the downloaded library. */
    val bytes: StateFlow<Long> = store.bytes
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val shared = Mutex() // dictionary and links file: one download at a time

    @Volatile private var memo: Pair<String?, LibraryDto>? = null

    /** The server's library (cached per build). */
    suspend fun library(): Load<LibraryDto> {
        if (fetcher.build == null) fetcher.refreshBuild() // a stale cached listing would hide updates
        memo?.let { (b, m) -> if (b == fetcher.build) return Load.Ok(m) }
        val r = fetcher.get(LISTING, { json ->
            withContext(Dispatchers.Default) { SefariaJson.decodeFromString(LibraryDto.serializer(), json) }
        }) { api.library(it) }
        if (r is Load.Ok && !r.offline) memo = fetcher.build to r.value
        return r
    }

    /** The server's downloadable books by title. */
    suspend fun available(): Load<Map<String, LibraryFileDto>> = library().map { l -> l.books.associateBy { it.book } }

    /** Bytes a book download adds beyond the book file: the links file and dictionary, until they are downloaded. */
    suspend fun firstDownloadBytes(): Long {
        val l = (library() as? Load.Ok)?.value ?: return 0
        return (if (store.hasDict(l.dict.sha256)) 0 else l.dict.bytes) +
            (l.links?.takeIf { store.linksSha() != it.sha256 }?.bytes ?: 0)
    }

    suspend fun scan() = store.scan()

    /** Brings the links file up to date when books are downloaded (in the background). */
    fun refreshLinks() {
        scope.launch {
            if (store.installed.value.isEmpty()) return@launch
            (library() as? Load.Ok)?.value?.let { ensureLinks(it) }
        }
    }

    /** True if the server has another file of an installed book. */
    fun updateAvailable(p: InstalledBook, available: LibraryFileDto?): Boolean =
        available != null && available.file != p.file

    fun download(book: String) {
        if (jobs[book]?.isActive == true) return
        _downloads.update { it + (book to DownloadState.Running(0, 0)) }
        jobs[book] = scope.launch {
            var err = fetch(book)
            if (err is AppError.BadResponse || err is AppError.NotFound) {
                // the listing may be stale (cached before the server's build changed): refetch it once
                memo = null
                fetcher.forget(LISTING)
                err = fetch(book)
            }
            _downloads.update { if (err == null) it - book else it + (book to DownloadState.Failed(err)) }
            jobs.remove(book)
        }
    }

    private suspend fun fetch(book: String): AppError? {
        val lib = when (val r = library()) {
            is Load.Ok -> r.value
            is Load.Err -> return r.error
        }
        val f = lib.books.firstOrNull { it.book == book } ?: return AppError.NotFound(book)
        _downloads.update { if (book in it) it + (book to DownloadState.Running(0, f.bytes)) else it }
        shared.withLock {
            if (!store.hasDict(lib.dict.sha256)) {
                get(lib.dict)?.let { return it }
                store.installDict(store.partFor(lib.dict.file), lib.dict.sha256)?.let { return AppError.BadResponse(it) }
            }
        }
        get(f) { done -> _downloads.update { if (book in it) it + (book to DownloadState.Running(done, f.bytes)) else it } }
            ?.let { return it }
        store.installBook(book, store.partFor(f.file), f.file)?.let { return AppError.BadResponse(it) }
        ensureLinks(lib) // a failure here leaves the book readable; its connections come from the server
        return null
    }

    private suspend fun ensureLinks(lib: LibraryDto) = shared.withLock {
        val l = lib.links ?: return@withLock
        if (store.linksSha() == l.sha256) return@withLock
        if (get(l) == null) store.installLinks(store.partFor(l.file), l.file)
    }

    private suspend fun get(f: LibraryFileDto, progress: (Long) -> Unit = {}): AppError? =
        downloader.download(api.libraryPath(f.file), f, store.partFor(f.file), progress)

    /** Stops a download; the partial file is kept so the next download resumes. */
    fun cancel(book: String) {
        jobs.remove(book)?.cancel()
        _downloads.update { it - book }
    }

    suspend fun delete(book: String) {
        cancel(book)
        store.delete(book)
    }

    private companion object {
        const val LISTING = "library"
    }
}
