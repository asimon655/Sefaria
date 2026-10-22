package org.asimon.sefaria.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.data.local.BookFile
import org.asimon.sefaria.data.local.BookReader
import org.asimon.sefaria.data.local.BookText
import org.asimon.sefaria.data.local.IndexFile
import org.asimon.sefaria.data.local.InstalledDao
import org.asimon.sefaria.data.local.LibraryFiles
import org.asimon.sefaria.data.local.LibraryReader
import org.asimon.sefaria.data.local.SearchBook

/**
 * What offline search keeps while the results page is open, apart from the reader's caches:
 * the downloaded books in search order with their files (one query, not one per book per page),
 * their category paths, the book and index files it opened, the last [BLOCKS] blocks it read,
 * and the last [BOOKS] books it decoded for hits. Searching the whole library opens thousands
 * of index files; keeping them here makes the next page and the next query fast, and lets them
 * all go when the page closes ([SearchRepository.release]), instead of for the app's lifetime.
 *
 * Made for the library as it was ([version]): an install or delete makes it stale.
 */
class SearchSession private constructor(
    private val reader: LibraryReader,
    private val dao: InstalledDao,
    val version: Long,
    /** Downloaded books in search order. */
    val books: List<SearchBook>,
) : BookReader {
    private val files = books.associateBy { it.book }
    private val opened = HashMap<String, BookFile?>() // title -> book file; null: unreadable
    private val indexes = HashMap<String, IndexFile?>() // title -> index file; null: none
    private val blocks = lru<String, List<String>>(BLOCKS)
    private val decoded = lru<String, Book>(BOOKS)
    @Volatile private var paths: Map<String, String>? = null

    val titles: List<String> = books.map { it.book }

    /** Each book's category path in the TOC ("Tanakh/Torah"), read once per session. */
    suspend fun paths(): Map<String, String> = paths ?: dao.bookPaths().associate { it.book to it.path }.also { paths = it }

    override suspend fun book(title: String): BookFile? {
        synchronized(this) { if (title in opened) return opened[title] }
        val file = files[title]?.file ?: return null
        val f = withContext(Dispatchers.IO) { reader.openBook(file, title) }
        return synchronized(this) { opened.getOrPut(title) { f } }
    }

    override suspend fun index(title: String): IndexFile? {
        synchronized(this) { if (title in indexes) return indexes[title] }
        val file = files[title]?.index?.takeIf { it.isNotEmpty() }
        val ix = if (file == null) null else withContext(Dispatchers.IO) { reader.openIndex(file, title) }
        return synchronized(this) { indexes.getOrPut(title) { ix } }
    }

    override suspend fun block(f: BookFile, t: BookText, i: Int): List<String>? {
        val k = f.file.name + "|" + t.lang + "|" + i
        synchronized(this) { blocks[k] }?.let { return it }
        val lines = withContext(Dispatchers.IO) { reader.readBlock(f, t, i) } ?: return null
        return synchronized(this) { blocks.getOrPut(k) { lines } }
    }

    /** A book decoded for a hit: from this session, or [load]ed now and kept here. */
    suspend fun decoded(title: String, load: suspend () -> Book?): Book? {
        synchronized(this) { decoded[title] }?.let { return it }
        val b = load() ?: return null
        return synchronized(this) { decoded.getOrPut(title) { b } }
    }

    companion object {
        const val BLOCKS = 32
        const val BOOKS = 64

        suspend fun open(files: LibraryFiles, reader: LibraryReader, dao: InstalledDao): SearchSession {
            val v = files.version // before the query: a change meanwhile makes this session stale
            return SearchSession(reader, dao, v, withContext(Dispatchers.IO) { dao.searchBooks() })
        }

        private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
        }
    }
}
