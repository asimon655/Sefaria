package org.asimon.sefaria.data

import org.asimon.sefaria.testDb
import org.asimon.sefaria.installFrom

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.local.CacheDao
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.SearchFilter
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TocNodeDto
import org.asimon.sefaria.data.repo.BuildInfo
import org.asimon.sefaria.data.repo.CachedFetcher
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.OfflineLibrary
import org.asimon.sefaria.data.repo.SearchRepository
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Search assessment on real data: the app's own SearchRepository, offline (its API points at a
 * closed port), over a set of real downloaded books, against the live server's library order
 * for the same books, plus the server's ranked results for comparison. Writes a Markdown report.
 *
 * Runs only with SEFARIA_LIBRARY (a build's library directory) and SEFARIA_SERVER (a running
 * sefaria-server for that build, e.g. http://127.0.0.1:8080, started with -rate-limit 0).
 * Optional: SEFARIA_QUERIES (queries.tsv), SEFARIA_REPORT (output file).
 */
class SearchAssessment {
    @get:Rule val tmp = TemporaryFolder()

    private object NoCache : CacheDao {
        override suspend fun get(key: String): CacheEntry? = null
        override suspend fun put(entry: CacheEntry) = Unit
        override suspend fun delete(key: String) = Unit
        override suspend fun touch(key: String, now: Long) = Unit
        override suspend fun bytes() = 0L
        override suspend fun trim(keepBytes: Long) = Unit
    }

    private data class Query(val q: String, val filter: SearchFilter, val label: String)

    @Test
    fun assess(): Unit = runBlocking {
        val lib = System.getenv("SEFARIA_LIBRARY")?.let(::File)
        val url = System.getenv("SEFARIA_SERVER")
        assumeTrue("SEFARIA_LIBRARY / SEFARIA_SERVER not set", lib != null && lib.isDirectory && url != null)
        val report = StringBuilder()
        fun say(s: String) { println(s); report.appendLine(s) }

        val m = SefariaJson.decodeFromString(LibraryDto.serializer(), File(lib, "manifest.json").readText())
        val all = System.getenv("SEFARIA_BOOKS") == "all" // the whole library, as after "download everything"
        // Left out: 34 books whose alt_structs hold a null ("Daf Yomi": null), which BookDto can't decode (a bug)
        val wanted = m.books.filter { b -> (all || wanted(b.book)) && !b.book.startsWith("Tosefta Kifshutah on ") && b.book != "Jerusalem Talmud Shekalim" }
        val db = testDb()
        val store = LibraryStore(tmp.root, db.library())
        store.installFrom(lib!!, m, wanted, link = true)

        val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val live = Api(http, { url!!.trimEnd('/') + "/" })
        val dead = Api(http, { "http://127.0.0.1:9/" })
        val library = LibraryRepository(live, CachedFetcher(live, NoCache, BuildInfo(), store), db.library())
        val offline = SearchRepository(dead, library, store, OfflineLibrary(store))
        val installed = store.installedNow().keys

        // category paths of the installed books, to give the server the same scope as the phone
        val paths = HashMap<String, String>()
        fun walk(nodes: List<TocNodeDto>, path: String) {
            for (n in nodes) if (n.isBook) paths[n.title] = path else walk(n.children, if (path.isEmpty()) n.en else "$path/${n.en}")
        }
        walk((library.toc() as Load.Ok).value.tree, "")

        val queries = queries().let { q -> if (System.getenv("SEFARIA_REPEAT") != null) q + q else q } // twice: cold, then warm
        say("# Search assessment\n")
        say("${installed.size} books installed offline (${"%.0f".format(wanted.sumOf { it.bytes } / 1e6)} MB of book files), ${queries.size} queries, up to $DEPTH hits each.\n")
        say("| # | query | scope | offline = server (library order) | offline hits | 1st page ms | $DEPTH hits ms | offline top 3 | server ranked top 3 (same books) |")
        say("|---|---|---|---|---|---|---|---|---|")
        var same = 0
        var differ = 0
        val firstPage = ArrayList<Double>()
        for ((i, qq) in queries.withIndex()) {
            val f = qq.filter
            val scopeBooks = installed.filter { t ->
                !f.scoped || t in f.books || f.categories.any { c -> paths[t] == c || paths[t].orEmpty().startsWith("$c/") }
            }.sorted()
            // offline, through the repository's own paging
            val off = ArrayList<SearchHitDto>()
            var cursor: String? = null
            var t1st = 0.0
            val t0 = System.nanoTime()
            var err: String? = null
            do {
                val ts = System.nanoTime()
                when (val r = offline.page(qq.q, f, cursor)) {
                    is Load.Ok -> { off += r.value.hits; cursor = r.value.next }
                    is Load.Err -> { err = r.error.toString(); cursor = null }
                }
                if (t1st == 0.0) t1st = (System.nanoTime() - ts) / 1e6
            } while (cursor != null && off.size < DEPTH)
            val tAll = (System.nanoTime() - t0) / 1e6
            firstPage += t1st
            // the server, library order, the same books
            val srv = ArrayList<SearchHitDto>()
            val sf = if (all) f else f.copy(books = scopeBooks, categories = emptyList()) // all installed: the server needs no book list
            if (scopeBooks.isNotEmpty()) {
                var c: String? = null
                do {
                    when (val r = live.search(qq.q, sf.copy(order = "library"), c, 50)) {
                        is ApiResult.Ok -> { srv += r.value.hits; c = r.value.next }
                        else -> { c = null; err = (err ?: "") + " server: $r" }
                    }
                } while (c != null && srv.size < DEPTH)
            }
            val ranked = if (scopeBooks.isEmpty()) emptyList() else (live.search(qq.q, sf, null, 3) as? ApiResult.Ok)?.value?.hits.orEmpty()
            val a = off.take(DEPTH).map { it.ref + "/" + it.match }
            val b = srv.take(DEPTH).map { it.ref + "/" + it.match }
            val verdict = when {
                err != null && off.isEmpty() && srv.isEmpty() -> "error: $err"
                a == b -> {
                    same++
                    val snip = off.zip(srv).filter { it.first.snippet != it.second.snippet }
                    if (snip.isEmpty()) "yes" else "yes; ${snip.size} snippets differ, e.g. `${snip[0].first.snippet.take(80)}` vs `${snip[0].second.snippet.take(80)}`"
                }
                else -> {
                    differ++
                    val at = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
                    "**no** at #$at: ${a.getOrNull(at) ?: "-"} vs ${b.getOrNull(at) ?: "-"} (${a.size} vs ${b.size})"
                }
            }
            fun top(h: List<SearchHitDto>) = h.take(3).joinToString(", ") { it.human }.ifEmpty { "-" }
            say("| ${i + 1} | ${qq.q.replace("|", "\\|")} | ${qq.label} | $verdict | ${off.size}${if (cursor != null) "+" else ""} | ${"%.0f".format(t1st)} | ${"%.0f".format(tAll)} | ${top(off)} | ${top(ranked)} |")
        }
        firstPage.sort()
        say("\nParity: $same equal, $differ different. First page: median ${"%.0f".format(firstPage[firstPage.size / 2])} ms, " +
            "p90 ${"%.0f".format(firstPage[(firstPage.size * 9) / 10])} ms, max ${"%.0f".format(firstPage.last())} ms.")
        System.getenv("SEFARIA_REPORT")?.let { File(it).writeText(report.toString()) }
    }

    private fun queries(): List<Query> {
        val out = ArrayList<Query>()
        val tsv = File(System.getenv("SEFARIA_QUERIES") ?: "/Volumes/KINGSTON/sefaria_dev/server/internal/search/testdata/queries.tsv")
        if (tsv.isFile && System.getenv("SEFARIA_EXTRA_ONLY") == null) for (line in tsv.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.removePrefix("!").split('\t')
            out += Query(f[0], filter(f.getOrElse(1) { "-" }), f.getOrElse(1) { "-" })
        }
        for (q in EXTRA) out += Query(q, SearchFilter(), "-")
        return out
    }

    private fun filter(p: String): SearchFilter {
        var f = SearchFilter()
        if (p == "-") return f
        for (kv in p.split('&')) {
            val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            f = when (k) {
                "category" -> f.copy(categories = f.categories + v)
                "book" -> f.copy(books = f.books + v.replace('_', ' '))
                "lang" -> f.copy(lang = v)
                "expand" -> f.copy(expand = v != "0")
                "forms" -> f.copy(allForms = v == "all")
                "drop" -> f.copy(drop = f.drop + v)
                else -> f
            }
        }
        return f
    }

    private fun wanted(t: String) =
        t in TORAH || t in BAVLI || t == "Psalms" || t == "Pirkei Avot" || t == "Mishnah Berurah" ||
            t.startsWith("Mishnah ") || t.startsWith("Shulchan Arukh, ") || t.startsWith("Rashi on ") && t.removePrefix("Rashi on ") in TORAH ||
            t == "Mishneh Torah, Sabbath" || t == "Mishneh Torah, Repentance" || t == "Sefer HaChinukh" || t == "Midrash Tanchuma"

    private companion object {
        const val DEPTH = 60
        val TORAH = setOf("Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy")
        val BAVLI = setOf(
            "Berakhot", "Shabbat", "Eruvin", "Pesachim", "Yoma", "Sukkah", "Beitzah", "Rosh Hashanah", "Taanit",
            "Megillah", "Moed Katan", "Chagigah", "Yevamot", "Ketubot", "Nedarim", "Nazir", "Sotah", "Gittin",
            "Kiddushin", "Bava Kamma", "Bava Metzia", "Bava Batra", "Sanhedrin", "Makkot", "Shevuot", "Avodah Zarah",
            "Horayot", "Zevachim", "Menachot", "Chullin", "Bekhorot", "Arakhin", "Temurah", "Keritot", "Meilah",
            "Tamid", "Niddah",
        )
        // How people type: single words, names with and without abbreviations, unquoted phrases,
        // niqqud, maqaf, refs typed as text, English with and without typos.
        val EXTRA = listOf(
            "שבת", "מוקצה", "תפילין", "נר חנוכה", "לשון הרע",
            "רבי עקיבא", "ר' עקיבא", "ר\"ע", "אמר רבי יוחנן", "\"אמר רבי יוחנן\"",
            "ואהבת לרעך כמוך", "\"ואהבת לרעך כמוך\"", "בָּרָא", "כל־ישראל", "ה' אחד",
            "שולחן ערוך", "בראשית א", "ברכות ב.",
            "Shabbat", "Shabat", "Rabbi Akiva", "love your neighbor", "\"in the beginning\"", "tefillin", "Moses",
            "שב*", "תפי*", "Sha*", "\"אם אין אני לי מי לי\"", "קקקקק",
        )
    }
}
