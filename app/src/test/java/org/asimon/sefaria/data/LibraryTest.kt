package org.asimon.sefaria.data

import kotlinx.coroutines.test.runTest
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.Expansion
import org.asimon.sefaria.core.text.SearchMatch
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.local.IndexFile
import org.asimon.sefaria.data.remote.SearchHitDto
import org.asimon.sefaria.data.local.BookFile
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.inflate
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SearchDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.datasource.OfflineLinks
import org.asimon.sefaria.data.datasource.OfflineSearch
import org.asimon.sefaria.data.datasource.OfflineText
import org.asimon.sefaria.testDb
import org.asimon.sefaria.installFrom
import org.asimon.sefaria.data.remote.LibraryDto
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.DataFormatException
import java.util.zip.Deflater

/**
 * The downloaded library answers as the server does. The fixture (src/test/resources/library)
 * is written by the server's TestLibrary: library files built from its test corpus, and that
 * server's responses in server/. Regenerate with
 * SFB_FIXTURE_DIR=<this app>/app/src/test/resources/library go test ./internal/api -run TestLibrary$
 */
class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val db = testDb()
    private lateinit var store: Library
    private lateinit var offlineLinks: OfflineLinks
    private lateinit var offlineText: OfflineText
    private lateinit var offlineSearch: OfflineSearch
    private val books = HashMap<String, Book>()

    private fun server(name: String) = File(javaClass.getResource("/library/server/$name.json")!!.toURI()).readText()

    @Before
    fun setUp() = runTest {
        val src = File(javaClass.getResource("/library/manifest.json")!!.toURI()).parentFile
        store = Library(tmp.root, db.installed())
        store.installFrom(src, SefariaJson.decodeFromString(LibraryDto.serializer(), File(src, "manifest.json").readText()))
        offlineLinks = OfflineLinks(store.reader)
        offlineText = OfflineText(store.reader, offlineLinks)
        offlineSearch = OfflineSearch(store.reader)
        for (t in listOf("Genesis", "Pesach Haggadah", "Rashi on Genesis", "Leviticus")) {
            books[t] = Book(SefariaJson.decodeFromString(BookDto.serializer(), store.reader.get(t, "book:$t")!!))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun ref(book: String, s: String) = Ref.parse(Catalog(listOf(books.getValue(book))), s)

    @Test
    fun installedBooks() = runTest {
        assertEquals(setOf("Genesis", "Pesach Haggadah", "Rashi on Genesis", "Leviticus"), store.files.installedNow().keys)
        // a book's size counts its index file
        val g = store.files.installedNow().getValue("Genesis")
        assertEquals(File(tmp.root, g.file).length() + tmp.root.listFiles { f -> f.name.endsWith(".sfi") }!!
            .single { org.asimon.sefaria.data.local.IndexFile(it).header.book == "Genesis" }.length(), g.bytes)
        // the library's size is every installed file's
        assertEquals(tmp.root.listFiles { f -> f.extension in setOf("sfb", "sfi", "sfl", "dict", "forms") }!!.sumOf { it.length() }, store.files.bytes.first())
        assertNull(store.reader.get("Genesis", "text:Genesis.1")) // only metadata is served as JSON
        assertEquals(3, books.getValue("Genesis").dto.versions.size)
    }

    @Test
    fun sectionsMatchServer() = runTest {
        for ((name, book, r) in listOf(
            Triple("text-genesis-1", "Genesis", "Genesis 1"),
            Triple("text-genesis-2", "Genesis", "Genesis 2"),
            Triple("text-kadesh", "Pesach Haggadah", "Pesach Haggadah, Kadesh"),
            Triple("text-rashi-1-1", "Rashi on Genesis", "Rashi on Genesis 1:1"),
        )) {
            val t = SefariaJson.decodeFromString(TextDto.serializer(), server(name))
            val s = t.sections.single()
            val want = SectionText(s.ref, s.heRef, s.first, s.he, s.en, t.versions, s.linkCounts)
            assertEquals(name, want, offlineText.section(ref(book, r)))
        }
    }

    @Test
    fun linksMatchServer() = runTest {
        for ((name, book, r) in listOf(
            Triple("links-genesis-1-1", "Genesis", "Genesis 1:1"),
            Triple("links-genesis-1-2", "Genesis", "Genesis 1:2"),
            Triple("links-genesis-2-1", "Genesis", "Genesis 2:1"),
            Triple("links-genesis-2", "Genesis", "Genesis 2"),
            Triple("links-genesis-3-1", "Genesis", "Genesis 3:1"),
            Triple("links-rashi-1-1-2", "Rashi on Genesis", "Rashi on Genesis 1:1:2"),
            Triple("links-kadesh-1", "Pesach Haggadah", "Pesach Haggadah, Kadesh 1"),
        )) {
            assertEquals(name, SefariaJson.decodeFromString(LinksDto.serializer(), server(name)), offlineLinks.links(ref(book, r)))
        }
        assertEquals(
            SefariaJson.decodeFromString(LinksDto.serializer(), server("withtext-rashi")),
            offlineLinks.links(ref("Genesis", "Genesis 1:1"), only = "Rashi on Genesis", withText = true),
        )
        assertEquals(
            SefariaJson.decodeFromString(LinksDto.serializer(), server("withtext-haggadah")),
            offlineLinks.links(ref("Genesis", "Genesis 2"), only = "Pesach Haggadah", withText = true),
        )
    }

    // The server's search order: texts, then commentaries, each in catalog order.
    private val searchOrder = listOf("Genesis", "Pesach Haggadah", "Leviticus", "Rashi on Genesis")

    /** All hits for [q] in library order, as SearchRepository pages them offline: tier 1, then tier 2. */
    private suspend fun libraryOrder(q: String, lang: String, x: Expansion = Expansion(), only: List<String> = searchOrder): List<SearchHitDto> {
        val clauses = SearchMatch.expand(SearchText.parse(q), store.reader.forms(), x)
        return (1..2).flatMap { tier -> only.flatMap { t -> offlineSearch.search(books.getValue(t), lang, clauses, tier, -1, 50)!!.map { it.second } } }
    }

    @Test
    fun searchMatchesServer() = runTest {
        for ((name, q, x, only) in listOf(
            Quad("search-he", "he1", Expansion(), searchOrder),
            Quad("search-prefix", "rash*", Expansion(), searchOrder),
            Quad("search-phrase-en", "\"en0 x\"", Expansion(), searchOrder),
            Quad("search-forms", "שבת", Expansion(), searchOrder),
            Quad("search-forms-sheh", "שה", Expansion(), searchOrder),
            Quad("search-forms-all", "אמר", Expansion(all = true), searchOrder),
            Quad("search-forms-drop", "שבת", Expansion(drop = setOf("השבת")), searchOrder),
            Quad("search-forms-off", "שבת", Expansion(off = true), searchOrder),
            Quad("search-forms-scope", "שבת", Expansion(), listOf("Genesis", "Leviticus")),
        )) {
            val want = SefariaJson.decodeFromString(SearchDto.serializer(), server(name))
            assertEquals(name, want.hits, libraryOrder(q, want.lang, x, only))
        }
        // paging: after the first hit's slot, the next one
        val rash = SearchMatch.expand(SearchText.parse("rash*"), null)
        val rashi = offlineSearch.search(books.getValue("Rashi on Genesis"), "he", rash, 1, 0, 1)!!
        assertEquals(listOf(1), rashi.map { it.first })
    }

    private data class Quad(val name: String, val q: String, val x: Expansion, val only: List<String>)

    @Test
    fun indexFindsEveryWord() = runTest {
        for (t in searchOrder) {
            val f = store.reader.book(t)!!
            val ix = store.reader.index(t)!!
            for (text in f.header.texts) for (bi in text.blocks.indices) for (line in store.reader.block(f, text, bi)!!) {
                for (w in SearchMatch.tokens(line)) assertTrue("$t ${text.lang} $w in block $bi", bi in ix.blocks(text.lang, w))
            }
        }
    }

    @Test
    fun indexHashMatchesServer() {
        // bookfile.Hash("שבת"), printed by the Go implementation
        assertEquals(java.lang.Long.parseUnsignedLong("6475771734170328626"), IndexFile.hash("שבת"))
    }

    @Test
    fun deleteDropsLinksWithLastBook() = runTest {
        for (t in searchOrder) store.installer.delete(t)
        assertTrue(store.files.installedNow().isEmpty())
        assertNull(store.reader.linksFile())
        assertNull(store.reader.forms())
        assertNull(offlineLinks.links(ref("Genesis", "Genesis 1:1")))
    }

    @Test
    fun restartReadsTheTable() = runTest {
        val before = store.files.installedNow()
        // a new store over the same folder and table (the app restarted) knows the books,
        // opens them only when read, and keeps their index files
        val again = Library(tmp.root, db.installed())
        assertEquals(before, again.files.installedNow())
        assertTrue(again.reader.hasIndex("Genesis"))
        assertEquals("Genesis", again.reader.book("Genesis")!!.title)
        assertTrue(again.reader.index("Genesis") != null)
        assertTrue(again.reader.linksFile() != null)
        assertTrue(again.reader.forms() != null)
    }

    @Test
    fun tidyCleansUpAnInterruptedInstall() = runTest {
        val genesis = store.files.installedNow().getValue("Genesis").file
        // a run ended between writing a file and recording it, and after a file's row went
        val orphan = File(tmp.root, "0".repeat(64) + ".sfb").apply { writeText("never recorded") }
        File(tmp.root, store.files.installedNow().getValue("Leviticus").file).delete()
        val part = File(tmp.root, "1".repeat(64) + ".sfb.part").apply { writeText("half a download") }
        val again = Library(tmp.root, db.installed())
        assertTrue(orphan.exists()) // a restart leaves them for the maintenance worker
        again.files.tidy()
        assertEquals(setOf("Genesis", "Pesach Haggadah", "Rashi on Genesis"), again.files.installedNow().keys)
        assertEquals(genesis, again.reader.bookFile("Genesis"))
        assertTrue(!orphan.exists())
        assertTrue(part.exists()) // its download resumes
    }

    @Test
    fun presetDictionary() {
        val dict = "shared words ".repeat(20).toByteArray()
        val d = Deflater(9).apply { setDictionary(dict); setInput("shared words again".toByteArray()); finish() }
        val z = ByteArray(256).let { it.copyOf(d.deflate(it)) }
        assertEquals("shared words again", inflate(z, dict).decodeToString())
        try {
            inflate(z, null)
            error("inflated without the dictionary")
        } catch (_: DataFormatException) {
        }
    }

    @Test
    fun rejectsOtherFiles() = runTest {
        val bad = tmp.newFile("x.part").apply { writeText("not a book") }
        assertTrue(store.installer.installBook("Genesis", bad, "x.sfb")!!.startsWith("not a book file"))
        val other = File(tmp.root, store.files.installedNow().getValue("Rashi on Genesis").file).copyTo(tmp.newFile("y.part"), overwrite = true)
        assertEquals("not a book file of Genesis", store.installer.installBook("Genesis", other, "y.sfb"))
        try {
            BookFile(File(tmp.root, store.files.installedNow().getValue("Genesis").file)) { null }
            error("opened without its dictionary")
        } catch (_: DataFormatException) {
        }
    }
}
