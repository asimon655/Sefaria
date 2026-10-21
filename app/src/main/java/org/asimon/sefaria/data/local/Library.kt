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

/** Server responses available without the network, by book title and cache key; null if absent. */
fun interface OfflineSource {
    suspend fun get(book: String, key: String): String?
}

/**
 * The downloaded library (SERVER_SPEC §7a), kept as downloaded in [dir]: book files
 * (<sha256>.sfb), their index files (<sha256>.sfi), the dictionary they were compressed with
 * (<sha256>.dict), the links file (<sha256>.sfl) and the Hebrew forms table, inflated
 * (<sha256 of the download>.forms). Reads inflate one block at a time; the last few
 * blocks, link tables and book entries stay decoded in small LRUs.
 *
 * The installed_file table ([dao]) says which file holds what, so books and their sizes are
 * queries, and book and index files open when first read, not at start.
 */
class LibraryStore(private val dir: File, private val dao: LibraryDao) : OfflineSource {
    private val lock = Mutex()
    private var loaded = false
    private val dicts = HashMap<String, ByteArray>()
    private val opened = HashMap<String, BookFile>() // by file name
    private val openedIndexes = HashMap<String, IndexFile>() // by file name
    private var links: LinksFile? = null
    private var forms: Pair<String, FormsFile>? = null // (sha256 of the download, table)
    private var rows: Map<String, InstalledFile>? = null // installed_file by "<kind>|<book>", read once; dropped on every write

    /** Downloaded books by title. */
    val installed: Flow<Map<String, InstalledBook>> = dao.installedBooks().map { l -> l.associateBy { it.book } }

    /** Disk used by the library: books, index files, links file, forms table and dictionary. */
    val bytes: Flow<Long> = dao.installedBytes()

    private val blocks = lru<String, List<String>>(16) // "<file>|<lang>|<block>" -> lines
    private val records = lru<Int, LinkRecords>(4) // links file book index -> records
    private val metas = lru<Int, String>(256) // links file book index -> entry JSON

    /** Where a download of [file] is written before it is installed. */
    fun partFor(file: String) = File(dir, "$file.part")

    /** Loads the shared files now (they load on first use otherwise). */
    suspend fun load() = locked { }

    /** Downloaded books by title, now. */
    suspend fun installedNow(): Map<String, InstalledBook> = locked { dao.installedBooksNow().associateBy { it.book } }

    /** Downloaded books in offline search order (LibraryDao.searchOrder). */
    suspend fun searchOrder(): List<String> = dao.searchOrder()

    /** The book file installed for [title] (without opening it). */
    suspend fun bookFile(title: String): String? = locked { installedRow(FileKind.BOOK, title)?.file }

    /** The links file's sha256 (its name), if one is installed. */
    suspend fun linksSha(): String? = locked { links?.file?.name?.substringBefore('.') }

    suspend fun hasDict(sha: String): Boolean = locked { sha in dicts }

    suspend fun book(title: String): BookFile? = locked { openBook(title) }

    suspend fun linksFile(): LinksFile? = locked { links }

    /** A downloaded book's index file, if it has one built for its installed book file. */
    suspend fun index(title: String): IndexFile? = locked { openIndex(title) }

    /** Whether a downloaded book has its index file (without opening it). */
    suspend fun hasIndex(title: String): Boolean = locked { installedRow(FileKind.INDEX, title) != null }

    /** The Hebrew forms table, if downloaded. */
    suspend fun forms(): Forms? = locked { forms?.second }

    /** The sha256 of the downloaded forms table, if any. */
    suspend fun formsSha(): String? = locked { forms?.first }

    /** A downloaded book's metadata, served for "book:<title>" as the server would. */
    override suspend fun get(book: String, key: String): String? =
        if (key == "book:$book") locked { openBook(book)?.meta() } else null

    /** Slots [start, end) of [t] in [b], or null if out of range. */
    suspend fun lines(b: BookFile, t: BookText, start: Int, end: Int): List<String>? = locked {
        if (start < 0 || end > t.slots || start > end) return@locked null
        val out = ArrayList<String>(end - start)
        if (start == end) return@locked out
        for (i in t.blockOf(start)..t.blockOf(end - 1)) {
            val lines = blockLocked(b, t, i)
            val first = t.first(i)
            out.addAll(lines.subList(maxOf(start - first, 0), minOf(end, t.end(i)) - first))
        }
        out
    }

    /** All lines of block [i] of [t] (for scanning). */
    suspend fun block(b: BookFile, t: BookText, i: Int): List<String> = locked { blockLocked(b, t, i) }

    private fun blockLocked(b: BookFile, t: BookText, i: Int): List<String> =
        blocks.getOrPut(b.file.name + "|" + t.lang + "|" + i) { b.block(t, i) }

    /** Link records of the links file's book [i]. */
    suspend fun records(i: Int): LinkRecords? = locked { links?.let { l -> records.getOrPut(i) { l.records(i) } } }

    /** The links file's entry for book [i] (BookDto JSON). */
    suspend fun linkMeta(i: Int): String? = locked { links?.let { l -> metas.getOrPut(i) { l.meta(i) } } }

    /** Installs a verified dictionary download. */
    suspend fun installDict(part: File, sha: String): String? = locked {
        val dst = File(dir, "$sha.dict")
        if (!part.renameTo(dst)) return@locked "cannot install ${dst.name}"
        dicts[sha] = dst.readBytes()
        writeRows(emptyList(), listOf(InstalledFile(dst.name, FileKind.DICT, bytes = dst.length())))
        null
    }

    /** Installs a verified book download, replacing an older file of the same book (and its index). */
    suspend fun installBook(title: String, part: File, file: String): String? = locked {
        val b = try {
            open(part)
        } catch (e: Exception) {
            part.delete()
            return@locked "not a book file: ${e.message}"
        }
        if (b.title != title) {
            part.delete()
            return@locked "not a book file of $title"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@locked "cannot install $file"
        val old = listOfNotNull(
            installedRow(FileKind.BOOK, title)?.takeIf { it.file != file },
            installedRow(FileKind.INDEX, title)?.takeIf { it.sfb != file },
        )
        replace(old, InstalledFile(file, FileKind.BOOK, title, b.header.he, dst.length()))
        null
    }

    /** Installs a verified index file download for [title]'s installed book file. */
    suspend fun installIndex(title: String, part: File, file: String): String? = locked {
        val h = try {
            IndexFile(part).header
        } catch (e: Exception) {
            part.delete()
            return@locked "not an index file: ${e.message}"
        }
        if (h.book != title || h.sfb != installedRow(FileKind.BOOK, title)?.file) {
            part.delete()
            return@locked "index file of another book file"
        }
        if (h.norm != IndexFile.NORMALIZE_VERSION) { // built for another normaliser: search scans the book
            part.delete()
            return@locked null
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@locked "cannot install $file"
        val old = listOfNotNull(installedRow(FileKind.INDEX, title)?.takeIf { it.file != file })
        replace(old, InstalledFile(file, FileKind.INDEX, title, bytes = dst.length(), sfb = h.sfb, norm = h.norm))
        null
    }

    /** Installs a verified forms table download (zlib) by inflating it, replacing the older one. */
    suspend fun installForms(part: File, sha: String): String? = locked {
        val dst = File(dir, "$sha.forms")
        try {
            java.util.zip.InflaterInputStream(part.inputStream()).use { input -> dst.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            dst.delete()
            return@locked "not a forms table: ${e.message}"
        } finally {
            part.delete()
        }
        val table = try {
            FormsFile(dst)
        } catch (e: Exception) {
            dst.delete()
            return@locked "not a forms table: ${e.message}"
        }
        replace(listOfNotNull(installedRow(FileKind.FORMS)?.takeIf { it.file != dst.name }), InstalledFile(dst.name, FileKind.FORMS, bytes = dst.length()))
        forms = sha to table
        null
    }

    /** Installs a verified links file download, replacing the older one. */
    suspend fun installLinks(part: File, file: String): String? = locked {
        try {
            LinksFile(part)
        } catch (e: Exception) {
            part.delete()
            return@locked "not a links file: ${e.message}"
        }
        val dst = File(dir, file)
        if (!part.renameTo(dst)) return@locked "cannot install $file"
        replace(listOfNotNull(installedRow(FileKind.LINKS)?.takeIf { it.file != file }), InstalledFile(file, FileKind.LINKS, bytes = dst.length()))
        links = LinksFile(dst)
        records.clear()
        metas.clear()
        null
    }

    /** Removes a downloaded book; the links file goes with the last one. */
    suspend fun delete(title: String) = deleteAll(listOf(title))

    /** Removes downloaded books in one go; the links file and forms table go with the last one. */
    suspend fun deleteAll(titles: Collection<String>) = locked {
        remove(dao.installedOf(titles.toSet()))
        blocks.clear()
        if (!dao.hasBooks()) {
            remove(listOfNotNull(installedRow(FileKind.LINKS), installedRow(FileKind.FORMS)))
            links = null
            forms = null
            records.clear()
            metas.clear()
        }
    }

    /** Deletes partial downloads of files not in [keep] (files of an older build, which never resume). */
    suspend fun dropParts(keep: Set<String>) = locked {
        dir.listFiles { f -> f.name.endsWith(".part") && f.name.removeSuffix(".part") !in keep }.orEmpty().forEach { it.delete() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                loaded = true
                loadLocked()
            }
            block()
        }
    }


    private fun open(f: File) = BookFile(f, dicts::get)

    // installed_file rows from memory: search asks for thousands of books' rows per page.
    private suspend fun installedRow(kind: String, book: String = ""): InstalledFile? =
        (rows ?: dao.installedFiles().associateBy { it.kind + "|" + it.book }.also { rows = it })[kind + "|" + book]

    private suspend fun writeRows(drop: Collection<String>, put: List<InstalledFile>) {
        rows = null
        dao.replaceInstalled(drop, put)
    }

    /** [title]'s book file, opened on first use; an unreadable one is deleted (download again). */
    private suspend fun openBook(title: String): BookFile? {
        val row = installedRow(FileKind.BOOK, title) ?: return null
        opened[row.file]?.let { return it }
        return try {
            open(File(dir, row.file)).also { opened[row.file] = it }
        } catch (_: Exception) {
            remove(listOfNotNull(row, installedRow(FileKind.INDEX, title)))
            null
        }
    }

    private suspend fun openIndex(title: String): IndexFile? {
        val row = installedRow(FileKind.INDEX, title) ?: return null
        openedIndexes[row.file]?.let { return it }
        return try {
            IndexFile(File(dir, row.file)).also { openedIndexes[row.file] = it }
        } catch (_: Exception) {
            remove(listOf(row))
            null
        }
    }

    /** Records [new] in place of [old], then deletes the old files. */
    private suspend fun replace(old: List<InstalledFile>, new: InstalledFile) {
        writeRows(old.map { it.file }, listOf(new))
        old.forEach(::forget)
    }

    /** Deletes files and their rows. */
    private suspend fun remove(rows: List<InstalledFile>) {
        if (rows.isEmpty()) return
        writeRows(rows.map { it.file }, emptyList())
        rows.forEach(::forget)
    }

    private fun forget(row: InstalledFile) {
        File(dir, row.file).delete()
        opened.remove(row.file)
        openedIndexes.remove(row.file)
    }

    /**
     * Tidies up after a run that ended mid-install (MaintenanceWorker): files without a row (an
     * install writes the file before its row, and drops a replaced file's row before the file)
     * are deleted, and rows of files that are gone dropped. Partial downloads (.part) stay for
     * their downloads to resume. Not done at start: listing thousands of files held up the
     * first search, and a stray file only costs disk until then.
     */
    suspend fun tidy() = locked {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }.orEmpty().associateBy { it.name }
        val rows = dao.installedFiles()
        val known = rows.mapTo(HashSet()) { it.file }
        files.values.filter { it.name !in known }.forEach { it.delete() }
        remove(rows.filter { it.file !in files })
    }

    /**
     * Loads what every book shares: the dictionaries, links file and forms table. A file that
     * is missing or unreadable loses its row (download it again); book and index files open
     * when read.
     */
    private suspend fun loadLocked() {
        dir.mkdirs()
        for (r in dao.installedFiles()) {
            if (r.kind != FileKind.DICT && r.kind != FileKind.LINKS && r.kind != FileKind.FORMS) continue
            val f = File(dir, r.file)
            try {
                when (r.kind) {
                    FileKind.DICT -> dicts[f.name.substringBefore('.')] = f.readBytes()
                    FileKind.LINKS -> links = LinksFile(f)
                    FileKind.FORMS -> forms = f.name.substringBefore('.') to FormsFile(f)
                }
            } catch (_: Exception) {
                remove(listOf(r))
            }
        }
    }

    private fun <K, V> lru(n: Int) = object : LinkedHashMap<K, V>(n, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > n
    }
}
