package org.asimon.sefaria

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import org.asimon.sefaria.data.local.LibraryStore
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.remote.LibraryDto
import org.asimon.sefaria.data.remote.LibraryFileDto
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
suspend fun LibraryStore.installFrom(src: File, m: LibraryDto, books: List<LibraryFileDto> = m.books, link: Boolean = false) {
    load() // creates the folder
    fun part(name: String): File {
        val p = partFor(name)
        if (link) Files.createSymbolicLink(p.toPath(), File(src, name).toPath()) else File(src, name).copyTo(p, overwrite = true)
        return p
    }
    check(installDict(part(m.dict.file), m.dict.sha256) == null)
    for (b in books) {
        check(installBook(b.book, part(b.file), b.file) == null) { b.book }
        if (b.index.isNotEmpty()) check(installIndex(b.book, part(b.index), b.index) == null) { b.book }
    }
    m.links?.let { check(installLinks(part(it.file), it.file) == null) }
    m.forms?.let { check(installForms(part(it.file), it.sha256) == null) }
}
