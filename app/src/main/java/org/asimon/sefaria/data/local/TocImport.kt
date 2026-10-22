package org.asimon.sefaria.data.local

import androidx.room.Transactor
import androidx.room.execSQL
import androidx.room.useWriterConnection
import java.io.File

/** The user_version of /v1/toc.sqlite this app reads (SERVER_SPEC §6.3.3). */
const val TOC_DB_VERSION = 1L

/** The columns of toc_node, the same in the server's file and in [TocNode]. */
private const val TOC_COLUMNS = "id, parent, kind, en, he, title, desc_en, desc_he, short_en, short_he, dependent"

/**
 * Copies the category tree of [file] (as /v1/toc.sqlite serves it) into toc_node and stamps
 * it with the file's build id, which it returns. The file is attached to the writer connection
 * and its rows copied in one transaction, so the old tree stays if anything fails.
 */
suspend fun SefariaDb.importToc(file: File): String = useWriterConnection { c ->
    // ATTACH can't run inside a transaction
    c.usePrepared("ATTACH DATABASE ? AS src") {
        it.bindText(1, file.path)
        it.step()
    }
    try {
        val version = c.usePrepared("PRAGMA src.user_version") { it.step(); it.getLong(0) }
        check(version == TOC_DB_VERSION) { "toc.sqlite version $version, want $TOC_DB_VERSION" }
        val build = c.usePrepared("SELECT value FROM src.meta WHERE key = 'build_id'") { if (it.step()) it.getText(0) else "" }
        check(build.isNotEmpty()) { "toc.sqlite has no build id" }
        c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            execSQL("DELETE FROM toc_node")
            execSQL("INSERT INTO toc_node($TOC_COLUMNS) SELECT $TOC_COLUMNS FROM src.toc_node")
            usePrepared("INSERT OR REPLACE INTO synced(what, build) VALUES(?, ?)") {
                it.bindText(1, Synced.TOC)
                it.bindText(2, build)
                it.step()
            }
        }
        build
    } finally {
        c.execSQL("DETACH DATABASE src")
    }
}
