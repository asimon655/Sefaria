package org.asimon.sefaria.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** What a library file holds (SERVER_SPEC §7), in [ListedFile] and [InstalledFile]. */
object FileKind {
    const val BOOK = "book" // <sha256>.sfb
    const val INDEX = "index" // <sha256>.sfi, a book's search index
    const val DICT = "dict" // <sha256>.dict, the dictionary book files are compressed with
    const val LINKS = "links" // <sha256>.sfl, optional
    const val FILTERS = "filters" // <sha256>.sfw, the word filters for offline search, optional
    const val FORMS = "forms" // <sha256>.sff as listed, inflated to <sha256>.forms when installed
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

/** A downloaded book with its TOC category path ("Tanakh/Torah"; "" if not in the TOC), for a scoped search. */
data class BookPath(val book: String, val path: String)

/** The files of the downloaded library (installed_file): which file holds what. */
@Dao
abstract class InstalledDao {
    @Query("SELECT * FROM installed_file")
    abstract suspend fun installedFiles(): List<InstalledFile>

    /** A book's book or index file, or (with no book) the dictionary, links file, forms table or filter file. */
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

    /**
     * Downloaded books in offline search order (the server's library order) with their book and
     * index files: texts, then commentaries and targumim, each in TOC order; books missing from
     * the TOC go last, by title.
     */
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

    private companion object {
        /** Each book file with its size and its index file's. */
        const val INSTALLED_BOOKS = """SELECT b.book, b.he, b.file, b.bytes + COALESCE(i.bytes, 0) AS bytes
            FROM installed_file b LEFT JOIN installed_file i ON i.kind = 'index' AND i.book = b.book
            WHERE b.kind = 'book'"""
    }
}
