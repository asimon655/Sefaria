package org.asimon.sefaria.data

import kotlinx.coroutines.test.runTest
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.core.ref.Catalog
import org.asimon.sefaria.core.ref.Ref
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.local.BookFile
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.local.inflate
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.LinksDto
import org.asimon.sefaria.data.remote.SearchDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.data.repo.OfflineLibrary
import org.asimon.sefaria.data.repo.SectionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private lateinit var store: LibraryStore
    private lateinit var offline: OfflineLibrary
    private val books = HashMap<String, Book>()

    private fun server(name: String) = File(javaClass.getResource("/library/server/$name.json")!!.toURI()).readText()

    @Before
    fun setUp() = runTest {
        val src = File(javaClass.getResource("/library/manifest.json")!!.toURI()).parentFile
        src.listFiles { f -> f.isFile }!!.forEach { it.copyTo(File(tmp.root, it.name)) }
        store = LibraryStore(tmp.root)
        offline = OfflineLibrary(store)
        for (t in listOf("Genesis", "Pesach Haggadah", "Rashi on Genesis")) {
            books[t] = Book(SefariaJson.decodeFromString(BookDto.serializer(), store.get(t, "book:$t")!!))
        }
    }

    private fun ref(book: String, s: String) = Ref.parse(Catalog(listOf(books.getValue(book))), s)

    @Test
    fun installedBooks() = runTest {
        assertEquals(setOf("Genesis", "Pesach Haggadah", "Rashi on Genesis"), store.installed.value.keys)
        assertNull(store.get("Genesis", "text:Genesis.1|he=d,en=d")) // only metadata is served as JSON
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
            assertEquals(name, want, offline.section(ref(book, r)))
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
            assertEquals(name, SefariaJson.decodeFromString(LinksDto.serializer(), server(name)), offline.links(ref(book, r)))
        }
        assertEquals(
            SefariaJson.decodeFromString(LinksDto.serializer(), server("withtext-rashi")),
            offline.links(ref("Genesis", "Genesis 1:1"), only = "Rashi on Genesis", withText = true),
        )
        assertEquals(
            SefariaJson.decodeFromString(LinksDto.serializer(), server("withtext-haggadah")),
            offline.links(ref("Genesis", "Genesis 2"), only = "Pesach Haggadah", withText = true),
        )
    }

    @Test
    fun searchMatchesServer() = runTest {
        for ((name, q) in listOf("search-he" to "he1", "search-prefix" to "rash*", "search-phrase-en" to "\"en0 x\"")) {
            val want = SefariaJson.decodeFromString(SearchDto.serializer(), server(name))
            val terms = SearchText.parse(q)
            val got = listOf("Genesis", "Pesach Haggadah", "Rashi on Genesis").flatMap { t ->
                offline.search(books.getValue(t), want.lang, terms, -1, 20)!!.map { it.second }
            }
            assertEquals(name, want.hits, got)
        }
        // paging: after the first hit's slot, the next one
        val rashi = offline.search(books.getValue("Rashi on Genesis"), "he", SearchText.parse("rash*"), 0, 1)!!
        assertEquals(listOf(1), rashi.map { it.first })
    }

    @Test
    fun scanMatching() {
        val t = SearchText.parse("\"ויאמר אלהים\" אור*")
        assertTrue(OfflineLibrary.matches("וַיֹּאמֶר אֱלֹהִים יְהִי אוֹר", t))
        assertTrue(!OfflineLibrary.matches("ויאמר משה אלהים אור", t)) // not a phrase
        assertTrue(OfflineLibrary.matches("Café <b>au</b> lait", SearchText.parse("cafe"))) // diacritics folded, as the server's tokenizer does
    }

    @Test
    fun deleteDropsLinksWithLastBook() = runTest {
        for (t in listOf("Genesis", "Pesach Haggadah", "Rashi on Genesis")) store.delete(t)
        assertTrue(store.installed.value.isEmpty())
        assertNull(store.linksFile())
        assertNull(offline.links(ref("Genesis", "Genesis 1:1")))
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
        assertTrue(store.installBook("Genesis", bad, "x.sfb")!!.startsWith("not a book file"))
        val other = File(tmp.root, store.installed.value.getValue("Rashi on Genesis").file).copyTo(tmp.newFile("y.part"), overwrite = true)
        assertEquals("not a book file of Genesis", store.installBook("Genesis", other, "y.sfb"))
        try {
            BookFile(File(tmp.root, store.installed.value.getValue("Genesis").file)) { null }
            error("opened without its dictionary")
        } catch (_: DataFormatException) {
        }
    }
}
