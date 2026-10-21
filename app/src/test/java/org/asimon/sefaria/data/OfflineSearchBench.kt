package org.asimon.sefaria.data

import org.asimon.sefaria.testDb
import org.asimon.sefaria.installFrom

import kotlinx.coroutines.runBlocking
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.text.Expansion
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.repo.OfflineLibrary
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Offline search speed on the real library (ROADMAP Phase 6b exit check): a common word across
 * the Talmud, Shulchan Arukh and Mishnah Berurah. Runs only with SEFARIA_LIBRARY set to a
 * build's library directory; the files are linked into a temporary folder, never touched.
 */
class OfflineSearchBench {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun commonWordAcrossBigBooks() = runBlocking {
        val lib = System.getenv("SEFARIA_LIBRARY")?.let(::File)
        assumeTrue("SEFARIA_LIBRARY not set", lib != null && lib.isDirectory)
        val m = SefariaJson.decodeFromString(LibraryDto.serializer(), File(lib, "manifest.json").readText())
        val wanted = m.books.filter { b ->
            b.book.startsWith("Shulchan Arukh, ") || b.book.startsWith("Mishnah Berurah") || b.book in BAVLI
        }
        val store = LibraryStore(tmp.root, testDb().library())
        store.installFrom(lib!!, m, wanted, link = true)
        val offline = OfflineLibrary(store)
        val books = wanted.map { Book(SefariaJson.decodeFromString(BookDto.serializer(), store.get(it.book, "book:${it.book}")!!)) }
        val bytes = wanted.sumOf { it.bytes } / 1e6
        println("${books.size} books, %.0f MB".format(bytes))
        for (q in listOf("שבת", "תפילין", "\"אמר רבי יוחנן\"", "מוקצה")) {
            val clauses = SearchMatch.expand(SearchText.parse(q), store.forms(), Expansion())
            repeat(2) { run ->
                for (tier in 1..2) {
                    val t0 = System.nanoTime()
                    // the first page: up to 20 hits, book by book in order
                    var hits = 0
                    for (b in books) {
                        hits += offline.search(b, "he", clauses, tier, -1, 20 - hits)!!.size
                        if (hits == 20) break
                    }
                    val first = (System.nanoTime() - t0) / 1e6
                    // every hit of the tier, to see the cost of reading to the end
                    val t1 = System.nanoTime()
                    var all = 0
                    for (b in books) all += offline.search(b, "he", clauses, tier, -1, Int.MAX_VALUE)!!.size
                    val total = (System.nanoTime() - t1) / 1e6
                    println("${if (run == 0) "cold" else "warm"} $q tier $tier: first page %.0f ms; all %d hits %.0f ms".format(first, all, total))
                }
            }
        }
        // the same without index files: what a book downloaded before them costs
        val bare = OfflineLibrary(LibraryStore(tmp.newFolder(), testDb().library()).also { s ->
            s.installFrom(lib!!, m, wanted.map { it.copy(index = "") }, link = true)
        })
        val clauses = SearchMatch.expand(SearchText.parse("מוקצה"), store.forms(), Expansion())
        val t0 = System.nanoTime()
        var all = 0
        for (b in books) all += bare.search(b, "he", clauses, 1, -1, Int.MAX_VALUE)!!.size
        println("no index: מוקצה tier 1: all %d hits %.0f ms".format(all, (System.nanoTime() - t0) / 1e6))
    }

    private companion object {
        val BAVLI = setOf(
            "Berakhot", "Shabbat", "Eruvin", "Pesachim", "Yoma", "Sukkah", "Beitzah", "Rosh Hashanah", "Taanit",
            "Megillah", "Moed Katan", "Chagigah", "Yevamot", "Ketubot", "Nedarim", "Nazir", "Sotah", "Gittin",
            "Kiddushin", "Bava Kamma", "Bava Metzia", "Bava Batra", "Sanhedrin", "Makkot", "Shevuot", "Avodah Zarah",
            "Horayot", "Zevachim", "Menachot", "Chullin", "Bekhorot", "Arakhin", "Temurah", "Keritot", "Meilah",
            "Tamid", "Niddah",
        )
    }
}
