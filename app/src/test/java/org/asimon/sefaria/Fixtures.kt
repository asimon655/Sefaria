package org.asimon.sefaria

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import org.asimon.sefaria.data.local.Library
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto
import org.asimon.sefaria.data.remote.TocDto
import org.asimon.sefaria.data.remote.TocNodeDto
import java.io.File
import java.nio.file.Files

/** Reads src/test/resources/fixtures/<name>: JSON recorded from the real server, plus the shared ref vectors. */
fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

/** An empty database in memory (Room runs on the JVM with the bundled SQLite driver). */
fun testDb(): SefariaDb =
    Room.inMemoryDatabaseBuilder(ContextWrapper(null), SefariaDb::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

/**
 * Installs [books] of the listing [m] from [src] (a folder of library files) as downloads do,
 * with the shared files; [link] puts symbolic links in place of copies (for big libraries).
 */
suspend fun Library.installFrom(src: File, m: LibraryDto, books: List<LibraryFileDto> = m.books, link: Boolean = false) {
    reader.load() // creates the folder
    fun part(name: String): File {
        val p = files.partFor(name)
        if (link) Files.createSymbolicLink(p.toPath(), File(src, name).toPath()) else File(src, name).copyTo(p, overwrite = true)
        return p
    }
    check(installer.installDict(part(m.dict.file), m.dict.sha256) == null)
    for (b in books) {
        check(installer.installBook(b.book, part(b.file), b.file) == null) { b.book }
        if (b.index.isNotEmpty()) check(installer.installIndex(b.book, part(b.index), b.index) == null) { b.book }
    }
    m.links?.let { check(installer.installLinks(part(it.file), it.file) == null) }
    m.forms?.let { check(installer.installForms(part(it.file), it.sha256) == null) }
}

/** The SQL statements of a fixture .sql file (comments dropped, split at semicolons). */
fun sqlStatements(name: String): List<String> =
    fixture(name).lines().joinToString("\n") { it.substringBefore("--") }.split(';').map { it.trim() }.filter { it.isNotEmpty() }

/** [toc] as the server's /v1/toc.sqlite: the shared schema, its rows in pre-order, its build id. */
fun tocDbFile(toc: TocDto, dest: File, version: Int = 1): File {
    dest.delete()
    BundledSQLiteDriver().open(dest.path).use { c ->
        sqlStatements("toc_schema.sql").forEach { c.execSQL(it) }
        c.execSQL("PRAGMA user_version = $version")
        c.prepare("INSERT INTO meta VALUES('build_id', ?)").use { it.bindText(1, toc.buildId); it.step() }
        c.prepare("INSERT INTO toc_node VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { st ->
            var next = 0
            fun add(n: TocNodeDto, parent: Int?) {
                val id = next++
                st.reset()
                st.bindLong(1, id.toLong())
                if (parent == null) st.bindNull(2) else st.bindLong(2, parent.toLong())
                listOf(n.kind, n.en, n.he, n.title, n.desc.en, n.desc.he, n.short.en, n.short.he).forEachIndexed { i, v -> st.bindText(3 + i, v) }
                st.bindLong(11, if (n.dependent) 1 else 0)
                st.step()
                n.children.forEach { add(it, id) }
            }
            toc.tree.forEach { add(it, null) }
        }
    }
    return dest
}
