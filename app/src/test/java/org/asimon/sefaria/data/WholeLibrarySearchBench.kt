package org.asimon.sefaria.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.importToc
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.datasource.BuildInfo
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.repo.BookRepository
import org.asimon.sefaria.data.datasource.OfflineSearch
import org.asimon.sefaria.data.repo.SearchRepository
import org.asimon.sefaria.installFrom
import org.asimon.sefaria.testDb
import org.asimon.sefaria.tocDbFile
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Offline search over the whole downloaded library, as the results page asks for it: the first
 * page of each query through SearchRepository, with the server unreachable. Runs only with
 * SEFARIA_LIBRARY set to a build's library directory (its toc.json is read from the folder
 * above); the files are linked into a temporary folder, never touched.
 */
class WholeLibrarySearchBench {
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
    fun firstPages() = runBlocking {
        val lib = System.getenv("SEFARIA_LIBRARY")?.let(::File)
        assumeTrue("SEFARIA_LIBRARY not set", lib != null && lib.isDirectory)
        val m = SefariaJson.decodeFromString(LibraryDto.serializer(), File(lib, "manifest.json").readText())
        val db = testDb()
        val t0 = System.nanoTime()
        val store = Library(tmp.newFolder("library"), db.installed())
        store.installFrom(lib!!, m, link = true)
        val toc = SefariaJson.decodeFromString(TocDto.serializer(), File(lib.parentFile, "toc.json").readText())
        db.importToc(tocDbFile(toc, tmp.newFile("toc.sqlite")))
        println("installed ${m.books.size} books in %.0f s".format((System.nanoTime() - t0) / 1e9))

        // a server that refuses connections: every page is searched offline
        val api = Api(OkHttpClient(), { "http://127.0.0.1:1/" })
        val fetcher = CachedFetcher(api, NoCache, BuildInfo(), store.reader)
        val books = BookRepository(api, fetcher)
        val search = SearchRepository(api, books, store.files, store.reader, db.installed(), OfflineSearch(store.reader))
        fun heap(): Long { repeat(3) { System.gc(); Thread.sleep(200) }; val r = Runtime.getRuntime(); return r.totalMemory() - r.freeMemory() }
        val before = heap()
        val queries = System.getenv("BENCH_QUERIES")?.split(",") ?: listOf("שבת", "תפילין", "\"אמר רבי יוחנן\"", "קקקזזזק")
        for (run in listOf("cold", "warm")) for (q in queries) {
            val t = System.nanoTime()
            val r = search.page(q, SearchFilter(lang = "he"))
            val ms = (System.nanoTime() - t) / 1e6
            val hits = (r as? Load.Ok)?.value?.hits?.size
            println("$run $q: page 1 %.0f ms, $hits hits".format(ms))
        }
        println("heap held while the results page is open: %.1f MB more than before".format((heap() - before) / 1e6))
        search.release()
        println("heap held after it closes: %.1f MB more than before".format((heap() - before) / 1e6))
        db.close()
    }
}
