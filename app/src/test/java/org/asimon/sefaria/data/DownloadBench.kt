package org.asimon.sefaria.data

import org.asimon.sefaria.testDb
import kotlinx.coroutines.flow.first

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Throttler
import okio.buffer
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.DownloadState
import org.asimon.sefaria.data.repo.FileDownloader
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.di.AppModule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Downloading the whole library the way the phone does: the app's own DownloadRepository,
 * FileDownloader and LibraryStore, its OkHttp client (HTTP/1.1 to the dev server), the same
 * number of parallel downloads, and the app scope's dispatcher. Runs only with SEFARIA_SERVER
 * set (e.g. http://localhost:8080). Optional, to come closer to a phone's link:
 *
 *   SEFARIA_LATENCY_MS  added before every request (a round trip)
 *   SEFARIA_BANDWIDTH   bytes per second for all downloads together
 *   SEFARIA_PARALLEL    books downloading at once (default: the app's, DownloadRepository.PARALLEL)
 *   SEFARIA_BOOKS       only the first n books (a quick run)
 *   SEFARIA_DIR         where to download (default: a temporary folder, deleted after)
 */
class DownloadBench {
    @get:Rule val tmp = TemporaryFolder()

    private object NoCache : CacheDao {
        override suspend fun get(key: String): CacheEntry? = null
        override suspend fun put(entry: CacheEntry) = Unit
        override suspend fun delete(key: String) = Unit
        override suspend fun touch(key: String, now: Long) = Unit
        override suspend fun bytes() = 0L
        override suspend fun trim(keepBytes: Long) = Unit
    }

    @Test
    fun wholeLibrary() = runBlocking {
        val url = System.getenv("SEFARIA_SERVER")
        assumeTrue("SEFARIA_SERVER not set", url != null)
        val latency = System.getenv("SEFARIA_LATENCY_MS")?.toLong() ?: 0
        val bandwidth = System.getenv("SEFARIA_BANDWIDTH")?.toLong() ?: 0
        val parallel = System.getenv("SEFARIA_PARALLEL")?.toInt() ?: DownloadRepository.PARALLEL
        val dir = System.getenv("SEFARIA_DIR")?.let(::File)?.also { it.deleteRecursively(); it.mkdirs() } ?: tmp.root

        val throttler = Throttler().apply { if (bandwidth > 0) bytesPerSecond(bandwidth) }
        val client = AppModule.okHttp().newBuilder().addNetworkInterceptor { chain ->
            if (latency > 0) Thread.sleep(latency)
            val r = chain.proceed(chain.request())
            if (bandwidth <= 0) r
            else r.newBuilder().body(throttler.source(r.body.source()).buffer().asResponseBody(r.body.contentType(), r.body.contentLength())).build()
        }.build()
        val base = suspend { url!! }
        val api = Api(client, base)
        val db = testDb()
        val store = LibraryStore(File(dir, "library"), db.library())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) // AppModule.appScope
        val repo = DownloadRepository(
            api, CachedFetcher(api, NoCache, BuildInfo(), store), db.library(), store, FileDownloader(client, base), scope,
            parallel = parallel,
        )

        check(repo.refreshListing() == null)
        val listed = db.library().listedBooks().first()
        val books = listed.let { b -> System.getenv("SEFARIA_BOOKS")?.toInt()?.let(b::take) ?: b }
        val expected = books.sumOf { it.downloadBytes } + db.library().firstDownloadBytes().first()
        println("bench: ${books.size} books, ${expected / 1_000_000} MB, latency $latency ms, bandwidth ${if (bandwidth > 0) "${bandwidth / 1_000_000} MB/s" else "unlimited"}, $parallel parallel, ${System.getProperty("os.name")}")

        val t0 = System.nanoTime()
        fun secs() = (System.nanoTime() - t0) / 1e9
        val ticker = launch {
            while (true) {
                delay(10_000)
                val done = store.installedNow().size
                println("bench: %5.0f s  %4d/%d books  %5.1f MB/s".format(secs(), done, books.size, store.bytes.first() / 1e6 / secs()))
            }
        }
        repo.downloadAll(books.map { it.book })
        repo.awaitIdle()
        ticker.cancel()
        val s = secs()
        val failed = repo.downloads.value.filterValues { it is DownloadState.Failed }
        val bytes = store.bytes.first()
        val done = store.installedNow().size
        println("bench: done in %.1f s: %d books, %.0f MB, %.1f MB/s, %.0f books/s, %d failed%s".format(
            s, done, bytes / 1e6, bytes / 1e6 / s, done / s, failed.size,
            failed.entries.take(5).joinToString("") { "\n  ${it.key}: ${(it.value as DownloadState.Failed).error}" },
        ))
        scope.cancel()
    }

}
