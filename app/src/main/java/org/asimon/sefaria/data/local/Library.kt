package org.asimon.sefaria.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.asimon.sefaria.core.text.Forms
import org.asimon.sefaria.core.text.FormsFile
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.DataFormatException

/** Server responses available without the network, by book title and cache key; null if absent. */
fun interface OfflineSource {
    suspend fun get(book: String, key: String): String?
}

/**
 * How search reads downloaded books: [LibraryStore] for a one-off read, a search session
 * (data/repo/SearchSession.kt) for a whole search, which keeps what it opens to itself.
 */
interface BookReader {
    suspend fun book(title: String): BookFile?
    /** A book's index file, if it has one built for its installed book file. */
    suspend fun index(title: String): IndexFile?
    /** The lines of block [i] of [t]; null if the file can't be read (deleted meanwhile). */
    suspend fun block(f: BookFile, t: BookText, i: Int): List<String>?
}

/**
 * The downloaded library (SERVER_SPEC §7), kept as downloaded in [dir]: book files
 * (<sha256>.sfb), their index files (<sha256>.sfi), the dictionary they were compressed with
 * (<sha256>.dict), the links file (<sha256>.sfl) and the Hebrew forms table, inflated
 * (<sha256 of the download>.forms). Reads inflate one block at a time.
 *
 * What stays in memory, and for how long:
 * - for the app's lifetime, what every read needs: the dictionaries, the links file's header,
 *   the forms table's line offsets;
 * - in small LRUs, what the reader is reading: the last [BOOKS] book headers, [BLOCKS] blocks,
 *   [RECORDS] books' link records and [METAS] link entries;
 * - nothing for search: it reads through a session of its own ([openBook], [openIndex],
 *   [readBlock]), which ends with the search screen.
 *
 * The installed_file table ([dao]) says which file holds what, so books and their sizes are
 * queries, and book files open when first read, not at start.
 *
 * Locking: [writes] orders installs, deletes and the first load. Reads take no lock while they
 * read or inflate; the caches are guarded by `synchronized` for the moment of a lookup or an
 * insert, so the reader never waits behind a search's block reads. A read racing a delete may
 * find its file gone: it then answers null, as for a book that isn't downloaded.
 */
class LibraryStore(private val dir: File, private val dao: LibraryDao) : OfflineSource, BookReader {
    private val writes = Mutex()
    @Volatile private var loaded = false
    @Volatile private var dicts: Map<String, ByteArray> = emptyMap()
    @Volatile private var links: LinksFile? = null
    @Volatile private var forms: Pair<String, FormsFile>? = null // (sha256 of the download, table)

    /** Bumped by every install and delete: a search session made before a change is stale. */
    private val changes = AtomicLong()
    val version: Long get() = changes.get()

    /** Downloaded books by title. */
    val installed: Flow<Map<String, InstalledBook>> = dao.installedBooks().map { l -> l.associateBy { it.book } }

    /** Disk used by the library: books, index files, links file, forms table and dictionary. */
    val bytes: Flow<Long> = dao.installedBytes()

    // the reader's caches; each guarded by synchronized on itself
    private val books = lru<String, BookFile>(BOOKS) // title -> opened book file
    private val blocks = lru<String, List<String>>(BLOCKS) // "<file>|<lang>|<block>" -> lines
    private val records = lru<Int, LinkRecords>(RECORDS) // links file book index -> records
    private val metas = lru<Int, String>(METAS) // links file book index -> entry JSON

    /** Where a download of [file] is written before it is installed. */
    fun partFor(file: String) = File(dir, "$file.part")

    /** Loads the shared files now (they load on first use otherwise). */
    suspend fun load() = io { }

    /** Downloaded books by title, now. */
    suspend fun installedNow(): Map<String, InstalledBook> = io { dao.installedBooksNow().associateBy { it.book } }

    /** Downloaded books in offline search order (LibraryDao.searchOrder). */
    suspend fun searchOrder(): List<String> = dao.searchOrder()

    /** Downloaded books in search order with their files, for a search session. */
    suspend fun searchBooks(): List<SearchBook> = io { dao.searchBooks() }

    /** Each downloaded book's TOC category path, for a scoped search. */
    suspend fun bookPaths(): Map<String, String> = dao.bookPaths().associate { it.book to it.path }

    /** The book file installed for [title] (without opening it). */
    suspend fun bookFile(title: String): String? = io { row(FileKind.BOOK, title)?.file }

    /** The links file's sha256 (its name), if one is installed. */
    suspend fun linksSha(): String? = io { links?.file?.name?.substringBefore('.') }

    suspend fun hasDict(sha: String): Boolean = io { sha in dicts }

    /** [title]'s book file, from the reader's cache or opened now; null if not downloaded. */
    override suspend fun book(title: String): BookFile? = io {
        synchronized(books) { books[title] }?.let { return@io it }
        val row = row(FileKind.BOOK, title) ?: return@io null
        val f = openBook(row.file, title) ?: return@io null
        synchronized(books) { books.getOrPut(title) { f } }
    }

    suspend fun linksFile(): LinksFile? = io { links }

    /** A downloaded book's index file, opened now and not kept (search sessions keep their own). */
    override suspend fun index(title: String): IndexFile? = io {
        val row = row(FileKind.INDEX, title) ?: return@io null
        openIndex(row.file, title)
    }

    /** Whether a downloaded book has its index file (without opening it). */
    suspend fun hasIndex(title: String): Boolean = io { row(FileKind.INDEX, title) != null }

    /** The Hebrew forms table, if downloaded. */
    suspend fun forms(): Forms? = io { forms?.second }

    /** The sha256 of the downloaded forms table, if any. */
    suspend fun formsSha(): String? = io { forms?.first }

    /** A downloaded book's metadata, served for "book:<title>" as the server would. */
    override suspend fun get(book: String, key: String): String? =
        if (key == "book:$book") io { book(book)?.let { f -> readOrNull { f.meta() } } } else null

    /** Slots [start, end) of [t] in [b], or null if out of range or unreadable. */
    suspend fun lines(b: BookFile, t: BookText, start: Int, end: Int): List<String>? = io {
        if (start < 0 || end > t.slots || start > end) return@io null
        val out = ArrayList<String>(end - start)
        if (start == end) return@io out
        for (i in t.blockOf(start)..t.blockOf(end - 1)) {
            val lines = cachedBlock(b, t, i) ?: return@io null
            val first = t.first(i)
            out.addAll(lines.subList(maxOf(start - first, 0), minOf(end, t.end(i)) - first))
        }
        out
    }

    /** All lines of block [i] of [t], through the reader's cache. */
    override suspend fun block(f: BookFile, t: BookText, i: Int): List<String>? = io { cachedBlock(f, t, i) }

    /**
     * Block [i] of [t] for a search: the reader's copy if it has one, else read now and not kept,
     * so a search doesn't push out what the reader is reading. Blocking: call on a worker thread.
     */
    fun readBlock(f: BookFile, t: BookText, i: Int): List<String>? =
        synchronized(blocks) { blocks[key(f, t, i)] } ?: readOrNull { f.block(t, i) }

    /**
     * Opens book file [file] of [title] without caching it; an unreadable one is deleted with its
     * index (download it again). Blocking: call on a worker thread.
     */
    suspend fun openBook(file: String, title: String): BookFile? = try {
        BookFile(File(dir, file)) { dicts[it] }
    } catch (_: Exception) {
        if (File(dir, file).exists()) dropBroken(title, FileKind.BOOK) // gone: a delete raced this read
        null
    }

    /** Opens index file [file] of [title] without caching it; an unreadable one is deleted. Blocking. */
    suspend fun openIndex(file: String, title: String): IndexFile? = try {
        IndexFile(File(dir, file))
    } catch (_: Exception) {
        if (File(dir, file).exists()) dropBroken(title, FileKind.INDEX)
        null
    }

    /** Link records of the links file's book [i]. */
    suspend fun records(i: Int): LinkRecords? = io {
        val l = links ?: return@io null
        synchronized(records) { records[i] }?.let { return@io it }
        val r = readOrNull { l.records(i) } ?: return@io null
        synchronized(records) { records.getOrPut(i) { r } }
    }

    /** The links file's entry for book [i] (BookDto JSON). */
    suspend fun linkMeta(i: Int): String? = io {
        val l = links ?: return@io null
        synchronized(metas) { metas[i] }?.let { return@io it }
        val m = readOrNull { l.meta(i) } ?: return@io null
        synchronized(metas) { metas.getOrPut(i) { m } }
    }

    /** Installs a verified dictionary download. */
    suspend fun installDict(part: File, sha: String): String? = writing {
        val dst = File(dir, "$sha.dict")
        if (!part.renameTo(dst)) return@writing "cannot install ${dst.name}"
        dicts = dicts + (sha to dst.readBytes())
        dao.replaceInstalled(emptyList(), listOf(InstalledFile(dst.name, FileKind.DICT, bytes = dst.length())))
        null
    }

    /** Installs a verified book download, replacing an older file of the same book (and its index). */
    suspend fun installBook(title: String, part: File, file: String): String? = writing {
        val b = try {
            BookFile(part) { dicts[it] }
        } catch (e: Exception) {
            part.delete()
            return@writing "not a book file: ${e.message}"
        }
        if (b.title != title) {
            part.delete()
            return@writing "not a book file of $title"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        val old = listOfNotNull(
            row(FileKind.BOOK, title)?.takeIf { it.file != file },
            row(FileKind.INDEX, title)?.takeIf { it.sfb != file },
        )
        replace(old, InstalledFile(file, FileKind.BOOK, title, b.header.he, dst.length()))
        null
    }

    /** Installs a verified index file download for [title]'s installed book file. */
    suspend fun installIndex(title: String, part: File, file: String): String? = writing {
        val h = try {
            IndexFile(part).header
        } catch (e: Exception) {
            part.delete()
            return@writing "not an index file: ${e.message}"
        }
        if (h.book != title || h.sfb != row(FileKind.BOOK, title)?.file) {
            part.delete()
            return@writing "index file of another book file"
        }
        if (h.norm != IndexFile.NORMALIZE_VERSION) { // built for another normaliser: search scans the book
            part.delete()
            return@writing null
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        val old = listOfNotNull(row(FileKind.INDEX, title)?.takeIf { it.file != file })
        replace(old, InstalledFile(file, FileKind.INDEX, title, bytes = dst.length(), sfb = h.sfb, norm = h.norm))
        null
    }

    /** Installs a verified forms table download (zlib) by inflating it, replacing the older one. */
    suspend fun installForms(part: File, sha: String): String? = writing {
        val dst = File(dir, "$sha.forms")
        try {
            java.util.zip.InflaterInputStream(part.inputStream()).use { input -> dst.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            dst.delete()
            return@writing "not a forms table: ${e.message}"
        } finally {
            part.delete()
        }
        val table = try {
            FormsFile(dst)
        } catch (e: Exception) {
            dst.delete()
            return@writing "not a forms table: ${e.message}"
        }
        replace(listOfNotNull(row(FileKind.FORMS)?.takeIf { it.file != dst.name }), InstalledFile(dst.name, FileKind.FORMS, bytes = dst.length()))
        forms = sha to table
        null
    }

    /** Installs a verified links file download, replacing the older one. */
    suspend fun installLinks(part: File, file: String): String? = writing {
        try {
            LinksFile(part)
        } catch (e: Exception) {
            part.delete()
            return@writing "not a links file: ${e.message}"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@writing "cannot install $file"
        replace(listOfNotNull(row(FileKind.LINKS)?.takeIf { it.file != file }), InstalledFile(file, FileKind.LINKS, bytes = dst.length()))
        links = LinksFile(dst)
        synchronized(records) { records.clear() }
        synchronized(metas) { metas.clear() }
        null
    }

    /** Removes a downloaded book; the links file goes with the last one. */
    suspend fun delete(title: String) = deleteAll(listOf(title))

    /** Removes downloaded books in one go; the links file and forms table go with the last one. */
    suspend fun deleteAll(titles: Collection<String>) = writing {
        remove(dao.installedOf(titles.toSet()))
        synchronized(blocks) { blocks.clear() }
        if (!dao.hasBooks()) {
            remove(listOfNotNull(row(FileKind.LINKS), row(FileKind.FORMS)))
            links = null
            forms = null
            synchronized(records) { records.clear() }
            synchronized(metas) { metas.clear() }
        }
    }

    /** Deletes partial downloads of files not in [keep] (files of an older build, which never resume). */
    suspend fun dropParts(keep: Set<String>) = io {
        dir.listFiles { f -> f.name.endsWith(".part") && f.name.removeSuffix(".part") !in keep }.orEmpty().forEach { it.delete() }
    }

    /**
     * Tidies up after a run that ended mid-install (MaintenanceWorker): files without a row (an
     * install writes the file before its row, and drops a replaced file's row before the file)
     * are deleted, and rows of files that are gone dropped. Partial downloads (.part) stay for
     * their downloads to resume. Not done at start: listing thousands of files held up the
     * first search, and a stray file only costs disk until then.
     */
    suspend fun tidy() = writing {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().associateBy { it.name }
        val rows = dao.installedFiles()
        val known = rows.mapTo(HashSet()) { it.file }
        files.values.filter { it.name !in known }.forEach { it.delete() }
        remove(rows.filter { it.file !in files })
    }

    // Runs [block] on the IO pool once the shared files are loaded. No lock: see the class comment.
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        if (!loaded) writes.withLock { if (!loaded) { loadLocked(); loaded = true } }
        block()
    }

    // Runs [block] as the only write: installs and deletes one at a time.
    private suspend fun <T> writing(block: suspend () -> T): T = io { writes.withLock { block() } }

    private suspend fun row(kind: String, book: String = ""): InstalledFile? = dao.installed(kind, book)

    private fun key(f: BookFile, t: BookText, i: Int) = f.file.name + "|" + t.lang + "|" + i

    private fun cachedBlock(f: BookFile, t: BookText, i: Int): List<String>? {
        val k = key(f, t, i)
        synchronized(blocks) { blocks[k] }?.let { return it }
        val lines = readOrNull { f.block(t, i) } ?: return null
        return synchronized(blocks) { blocks.getOrPut(k) { lines } }
    }

    // A read that fails because the file went away (a delete) or is damaged answers null.
    private inline fun <T> readOrNull(f: () -> T): T? = try {
        f()
    } catch (_: IOException) {
        null
    } catch (_: DataFormatException) {
        null
    }

    /** Deletes [title]'s unreadable file of [kind] (a book file takes its index along). */
    private suspend fun dropBroken(title: String, kind: String) = writing {
        remove(listOfNotNull(row(kind, title), if (kind == FileKind.BOOK) row(FileKind.INDEX, title) else null))
    }

    /** Records [new] in place of [old], then deletes the old files. */
    private suspend fun replace(old: List<InstalledFile>, new: InstalledFile) {
        changes.incrementAndGet()
        dao.replaceInstalled(old.map { it.file }, listOf(new))
        if (new.kind == FileKind.BOOK) synchronized(books) { books.remove(new.book) }
        old.forEach(::forget)
    }

    /** Deletes files and their rows. */
    private suspend fun remove(rows: List<InstalledFile>) {
        if (rows.isEmpty()) return
        changes.incrementAndGet()
        dao.replaceInstalled(rows.map { it.file }, emptyList())
        rows.forEach(::forget)
    }

    private fun forget(row: InstalledFile) {
        File(dir, row.file).delete()
        if (row.kind == FileKind.BOOK) synchronized(books) { books.remove(row.book) }
    }

    /**
     * Loads what every book shares: the dictionaries, links file and forms table. A file that
     * is missing or unreadable loses its row (download it again); book and index files open
     * when read.
     */
    private suspend fun loadLocked() {
        dir.mkdirs()
        val d = HashMap<String, ByteArray>()
        for (r in dao.installedFiles()) {
            if (r.kind != FileKind.DICT && r.kind != FileKind.LINKS && r.kind != FileKind.FORMS) continue
            val f = File(dir, r.file)
            try {
                when (r.kind) {
                    FileKind.DICT -> d[f.name.substringBefore('.')] = f.readBytes()
                    FileKind.LINKS -> links = LinksFile(f)
                    FileKind.FORMS -> forms = f.name.substringBefore('.') to FormsFile(f)
                }
            } catch (_: Exception) {
                remove(listOf(r))
            }
        }
        dicts = d
    }

    private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
    }

    companion object {
        /** Book headers the reader keeps open: the books on screen and in the connections panel. */
        const val BOOKS = 32
        const val BLOCKS = 16
        const val RECORDS = 4
        const val METAS = 256
    }
}
