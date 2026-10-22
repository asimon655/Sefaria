package org.asimon.sefaria.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.data.remote.Bilingual
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.TocNodeDto

/** What a library file holds (SERVER_SPEC §7), in [ListedFile] and [InstalledFile]. */
object FileKind {
    const val BOOK = "book" // <sha256>.sfb
    const val INDEX = "index" // <sha256>.sfi, a book's search index
    const val DICT = "dict" // <sha256>.dict, the dictionary book files are compressed with
    const val LINKS = "links" // <sha256>.sfl
    const val FORMS = "forms" // <sha256>.sff as listed, inflated to <sha256>.forms when installed
}

/** A file the server's library offers for download (/v1/library), as of the build in [Synced]. */
@Entity(tableName = "listed_file", indices = [Index("book")])
data class ListedFile(
    @PrimaryKey val file: String,
    val kind: String,
    val book: String = "",
    val he: String = "",
    val bytes: Long,
    /** A book's index file, downloaded with it; "" if it has none. */
    @ColumnInfo(name = "index_file") val index: String = "",
    @ColumnInfo(name = "index_bytes") val indexBytes: Long = 0,
    /** Books: queued for download (or stopped by the network), so a restart resumes it. */
    @ColumnInfo(defaultValue = "0") val queued: Boolean = false,
) {
    val sha256 get() = file.substringBefore('.')

    /** The book's index file as a file to download, if it has one. */
    val indexFile get() = if (index.isEmpty()) null else ListedFile(index, FileKind.INDEX, book, he, indexBytes)

    /** What downloading the book takes on the phone: its book file and index. */
    val downloadBytes get() = bytes + indexBytes
}

/** A file of the downloaded library (files/library) and what it holds. */
@Entity(tableName = "installed_file", indices = [Index("book")])
data class InstalledFile(
    @PrimaryKey val file: String,
    val kind: String,
    /** Book and index files: the book's title; [he] only for book files. */
    val book: String = "",
    val he: String = "",
    val bytes: Long,
    /** Index files: the book file they were built for, and their normaliser version. */
    val sfb: String = "",
    val norm: Int = 0,
)

/** A downloaded book; [file] is its book file, [bytes] counts its index file too. */
data class InstalledBook(val book: String, val he: String, val file: String, val bytes: Long)

/** A downloaded book as search reads it: its book file and index file ("" if none), in search order. */
data class SearchBook(val book: String, val file: String, val index: String)

/** A book of the TOC under the node [root] (a child of the level asked for). */
data class BookUnder(val root: Int, val title: String)

/** A book's names, for offline completion; [id] is its TOC position. */
data class BookName(val id: Int, val title: String, val he: String, val dependent: Boolean)

/** A downloaded book with its TOC category path ("Tanakh/Torah"; "" if not in the TOC), for a scoped search. */
data class BookPath(val book: String, val path: String)

/** Which server build the stored copy of [what] ([LISTING], [TOC]) came from. */
@Entity(tableName = "synced")
data class Synced(@PrimaryKey val what: String, val build: String) {
    companion object {
        const val LISTING = "listing"
        const val TOC = "toc"
    }
}

/** A node of the library's category tree; [id] is its pre-order position, [parent] null at the top. */
@Entity(tableName = "toc_node", indices = [Index("parent"), Index("title")])
data class TocNode(
    @PrimaryKey val id: Int,
    val parent: Int?,
    val kind: String,
    val en: String,
    val he: String,
    val title: String,
    @ColumnInfo(name = "desc_en") val descEn: String,
    @ColumnInfo(name = "desc_he") val descHe: String,
    @ColumnInfo(name = "short_en") val shortEn: String,
    @ColumnInfo(name = "short_he") val shortHe: String,
    /** Books only: a commentary or targum, searched after the texts. */
    @ColumnInfo(defaultValue = "0") val dependent: Boolean = false,
)

/**
 * The library's own data: the server's listing, the downloaded files and the category tree.
 * Rows of the server's (listing, tree) are replaced whole, with the build they came from.
 */
@Dao
abstract class LibraryDao {
    // ---- the server's listing

    @Query("SELECT * FROM listed_file WHERE kind = 'book'")
    abstract fun listedBooks(): Flow<List<ListedFile>>

    @Query("SELECT * FROM listed_file WHERE kind = 'book' AND book = :book")
    abstract suspend fun listedBook(book: String): ListedFile?

    /** The listed dictionary, links file or forms table. */
    @Query("SELECT * FROM listed_file WHERE kind = :kind")
    abstract suspend fun listed(kind: String): ListedFile?

    /** Every file name the listing offers, index files included. */
    @Query("SELECT file FROM listed_file UNION SELECT index_file FROM listed_file WHERE index_file != ''")
    abstract suspend fun listedNames(): List<String>

    /**
     * What a first book download adds beyond the book: the listed dictionary, links file and
     * forms table that aren't downloaded (by sha256: an installed forms table is renamed).
     */
    @Query(
        """SELECT COALESCE(SUM(l.bytes), 0) FROM listed_file l WHERE l.kind IN ('dict', 'links', 'forms')
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = l.kind
             AND substr(i.file, 1, instr(i.file, '.')) = substr(l.file, 1, instr(l.file, '.')))"""
    )
    abstract fun firstDownloadBytes(): Flow<Long>

    /** Downloaded books, in the file the listing has, that lack the index file it offers. */
    @Query(
        """SELECT l.* FROM listed_file l JOIN installed_file b ON b.kind = 'book' AND b.file = l.file
           WHERE l.kind = 'book' AND l.index_file != ''
           AND NOT EXISTS (SELECT 1 FROM installed_file i WHERE i.kind = 'index' AND i.book = l.book)"""
    )
    abstract suspend fun missingIndexes(): List<ListedFile>

    /** Replaces the listing, keeping which books are queued. */
    @Transaction
    open suspend fun putListing(l: LibraryDto, build: String) {
        val queued = queuedBooks().toHashSet()
        clearListing()
        fun row(f: LibraryFileDto, kind: String) = ListedFile(f.file, kind, f.book, f.he, f.bytes, f.index, f.indexBytes, kind == FileKind.BOOK && f.book in queued)
        insertListed(
            l.books.map { row(it, FileKind.BOOK) } + row(l.dict, FileKind.DICT) +
                listOfNotNull(l.links?.let { row(it, FileKind.LINKS) }, l.forms?.let { row(it, FileKind.FORMS) })
        )
        stamp(Synced(Synced.LISTING, build))
    }

    @Query("DELETE FROM listed_file")
    protected abstract suspend fun clearListing()

    // ---- the download queue

    /** Books queued for download, as last saved. */
    @Query("SELECT book FROM listed_file WHERE queued AND kind = 'book'")
    abstract suspend fun queuedBooks(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM listed_file WHERE queued)")
    abstract suspend fun hasQueued(): Boolean

    /** Saves the queue: exactly [books] are queued (books not listed are left out). */
    @Transaction
    open suspend fun setQueued(books: Collection<String>) {
        clearQueued()
        // in chunks: a query has at most 32,766 parameters, and the library could outgrow one
        books.chunked(QUEUE_CHUNK).forEach { markQueued(it) }
    }

    @Query("UPDATE listed_file SET queued = 0 WHERE queued")
    protected abstract suspend fun clearQueued()

    @Query("UPDATE listed_file SET queued = 1 WHERE kind = 'book' AND book IN (:books)")
    protected abstract suspend fun markQueued(books: List<String>)

    @Insert
    protected abstract suspend fun insertListed(files: List<ListedFile>)

    // ---- the downloaded library

    @Query("SELECT * FROM installed_file")
    abstract suspend fun installedFiles(): List<InstalledFile>

    /** A book's book or index file, or (with no book) the dictionary, links file or forms table. */
    @Query("SELECT * FROM installed_file WHERE kind = :kind AND book = :book LIMIT 1")
    abstract suspend fun installed(kind: String, book: String = ""): InstalledFile?

    @Query("SELECT * FROM installed_file WHERE book IN (:books)")
    abstract suspend fun installedOf(books: Collection<String>): List<InstalledFile>

    @Query(INSTALLED_BOOKS)
    abstract fun installedBooks(): Flow<List<InstalledBook>>

    @Query(INSTALLED_BOOKS)
    abstract suspend fun installedBooksNow(): List<InstalledBook>

    @Query("SELECT EXISTS(SELECT 1 FROM installed_file WHERE kind = 'book')")
    abstract suspend fun hasBooks(): Boolean

    /** Disk used by the downloaded library. */
    @Query("SELECT COALESCE(SUM(bytes), 0) FROM installed_file")
    abstract fun installedBytes(): Flow<Long>

    /** Forgets the files [drop] and records [put], in one step. */
    @Transaction
    open suspend fun replaceInstalled(drop: Collection<String>, put: List<InstalledFile>) {
        if (drop.isNotEmpty()) dropInstalled(drop)
        if (put.isNotEmpty()) putInstalled(put)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putInstalled(files: List<InstalledFile>)

    @Query("DELETE FROM installed_file WHERE file IN (:files)")
    protected abstract suspend fun dropInstalled(files: Collection<String>)

    // ---- the category tree

    /** The stored tree, with the build it came from; null if none is stored. */
    @Transaction
    open suspend fun toc(): TocDto? {
        val build = synced(Synced.TOC) ?: return null
        return TocDto(build, tocTree(tocNodes()))
    }

    @Query("SELECT * FROM toc_node ORDER BY id")
    protected abstract suspend fun tocNodes(): List<TocNode>

    /** The children of [parent] (null: the top level), in TOC order. */
    @Query("SELECT * FROM toc_node WHERE parent IS :parent ORDER BY id")
    abstract suspend fun tocChildren(parent: Int?): List<TocNode>

    /** The category named [en] under [parent] (null: the top level). */
    @Query("SELECT * FROM toc_node WHERE parent IS :parent AND kind = 'category' AND en = :en LIMIT 1")
    abstract suspend fun tocCategory(parent: Int?, en: String): TocNode?

    /** Every book under each child of [parent] (a book child: itself), in TOC order. */
    @Query(
        """WITH RECURSIVE sub(root, id) AS (
             SELECT id, id FROM toc_node WHERE parent IS :parent
             UNION ALL SELECT sub.root, n.id FROM toc_node n JOIN sub ON n.parent = sub.id)
           SELECT sub.root AS root, n.title AS title FROM sub JOIN toc_node n ON n.id = sub.id
           WHERE n.kind = 'book' ORDER BY n.id"""
    )
    abstract suspend fun booksUnder(parent: Int?): List<BookUnder>

    /** Every book's names, in TOC order. */
    @Query("SELECT id, title, he, dependent FROM toc_node WHERE kind = 'book' ORDER BY id")
    abstract suspend fun bookNames(): List<BookName>

    /**
     * Downloaded books in offline search order (the server's library order): texts, then
     * commentaries and targumim, each in TOC order; books missing from the TOC go last, by title.
     */
    @Query(
        """SELECT b.book FROM installed_file b
            LEFT JOIN toc_node n ON n.kind = 'book' AND n.title = b.book
            WHERE b.kind = 'book'
            ORDER BY n.dependent, n.id IS NULL, n.id, b.book"""
    )
    abstract suspend fun searchOrder(): List<String>

    /** Downloaded books in search order ([searchOrder]) with their book and index files, in one query. */
    @Query(
        """SELECT b.book AS book, b.file AS file, COALESCE(i.file, '') AS `index` FROM installed_file b
            LEFT JOIN installed_file i ON i.kind = 'index' AND i.book = b.book
            LEFT JOIN toc_node n ON n.kind = 'book' AND n.title = b.book
            WHERE b.kind = 'book'
            ORDER BY n.dependent, n.id IS NULL, n.id, b.book"""
    )
    abstract suspend fun searchBooks(): List<SearchBook>

    /** Each downloaded book's category path in the TOC, for a scoped search. */
    @Query(
        """WITH RECURSIVE cat(id, path) AS (
             SELECT id, en FROM toc_node WHERE parent IS NULL AND kind = 'category'
             UNION ALL SELECT n.id, cat.path || '/' || n.en FROM toc_node n JOIN cat ON n.parent = cat.id
             WHERE n.kind = 'category')
           SELECT b.book AS book, COALESCE(cat.path, '') AS path FROM installed_file b
           LEFT JOIN toc_node n ON n.kind = 'book' AND n.title = b.book
           LEFT JOIN cat ON cat.id = n.parent
           WHERE b.kind = 'book'"""
    )
    abstract suspend fun bookPaths(): List<BookPath>

    // ---- builds

    @Query("SELECT build FROM synced WHERE what = :what")
    abstract suspend fun synced(what: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun stamp(s: Synced)

    private companion object {
        const val QUEUE_CHUNK = 10_000

        /** Each book file with its size and its index file's. */
        const val INSTALLED_BOOKS = """SELECT b.book, b.he, b.file, b.bytes + COALESCE(i.bytes, 0) AS bytes
            FROM installed_file b LEFT JOIN installed_file i ON i.kind = 'index' AND i.book = b.book
            WHERE b.kind = 'book'"""
    }
}

/** One node without its children (a level of the tree is read on its own). */
fun TocNode.dto() = TocNodeDto(kind, en, he, title, Bilingual(descEn, descHe), Bilingual(shortEn, shortHe), emptyList(), dependent)

/** The tree back from its toc_node rows (in id order). */
fun tocTree(rows: List<TocNode>): List<TocNodeDto> {
    val children = rows.groupBy { it.parent }
    fun node(r: TocNode): TocNodeDto = TocNodeDto(
        r.kind, r.en, r.he, r.title, Bilingual(r.descEn, r.descHe), Bilingual(r.shortEn, r.shortHe),
        children[r.id].orEmpty().map(::node), r.dependent,
    )
    return children[null].orEmpty().map(::node)
}
