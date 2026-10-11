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
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.asimon.sefaria.data.local.InstalledPack
import org.asimon.sefaria.data.local.PackStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.PackDto
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
 * Downloads a pack file, resuming a partial one with a Range request, and verifies its
 * size and sha256. Returns null on success; a failed checksum deletes the partial file.
 */
class PackDownloader(private val client: OkHttpClient, private val baseUrl: suspend () -> String) {
    suspend fun download(p: PackDto, part: File, progress: (Long) -> Unit = {}): AppError? = withContext(Dispatchers.IO) {
        val url = (baseUrl().trimEnd('/') + p.url).toHttpUrlOrNull()
            ?: return@withContext AppError.BadResponse("bad URL ${p.url}")
        try {
            var have = if (part.length() <= p.bytes) part.length() else 0L.also { part.delete() }
            if (have < p.bytes) {
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        206 -> Unit
                        200 -> have = 0
                        else -> return@withContext if (resp.code == 404) AppError.NotFound("pack ${p.book}")
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
            if (part.length() != p.bytes || sha256(part) != p.sha256) {
                part.delete()
                return@withContext AppError.BadResponse("checksum mismatch for ${p.book}")
            }
            progress(p.bytes)
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
 * Downloaded books (Phase 5): the server's listing, downloads in the app scope with
 * progress, and installed packs. KISS: downloads run in the app process instead of
 * WorkManager; a cancelled or interrupted download resumes where it stopped.
 */
class PackRepository(
    private val api: Api,
    private val fetcher: CachedFetcher,
    private val store: PackStore,
    private val downloader: PackDownloader,
    private val scope: CoroutineScope,
) {
    val installed: StateFlow<Map<String, InstalledPack>> = store.installed
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile private var memo: Pair<String?, Map<String, PackDto>>? = null

    /** The server's downloadable books by title (cached per build). */
    suspend fun available(): Load<Map<String, PackDto>> {
        if (fetcher.build == null) fetcher.refreshBuild() // a stale cached listing would hide updates
        memo?.let { (b, m) -> if (b == fetcher.build) return Load.Ok(m) }
        val r = fetcher.get(LISTING, { json ->
            withContext(Dispatchers.Default) { SefariaJson.decodeFromString(ListSerializer(PackDto.serializer()), json) }
        }) { api.packs(it) }.map { list -> list.associateBy { it.book } }
        if (r is Load.Ok && !r.offline) memo = fetcher.build to r.value
        return r
    }

    suspend fun scan() = store.scan()

    /** True if the server has a newer build of an installed book. */
    fun updateAvailable(p: InstalledPack, available: PackDto?): Boolean =
        available != null && !available.url.contains("/${p.build}/")

    fun download(p: PackDto) {
        if (jobs[p.book]?.isActive == true) return
        _downloads.update { it + (p.book to DownloadState.Running(0, p.bytes)) }
        jobs[p.book] = scope.launch {
            var err = fetch(p)
            if (err is AppError.BadResponse) {
                // the listing may be stale (cached before the server's packs changed): refetch it once
                memo = null
                fetcher.forget(LISTING)
                val fresh = (available() as? Load.Ok)?.value?.get(p.book)
                if (fresh != null && fresh.sha256 != p.sha256) err = fetch(fresh)
            }
            _downloads.update { if (err == null) it - p.book else it + (p.book to DownloadState.Failed(err)) }
            jobs.remove(p.book)
        }
    }

    private suspend fun fetch(p: PackDto): AppError? {
        val part = store.partFor(p.book, p.sha256)
        store.dropParts(p.book, keep = part)
        return downloader.download(p, part) { done ->
            _downloads.update { if (p.book in it) it + (p.book to DownloadState.Running(done, p.bytes)) else it }
        } ?: store.install(p.book, part)?.let { AppError.BadResponse(it) }
    }

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
        const val LISTING = "packs"
    }
}
