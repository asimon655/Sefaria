package org.asimon.sefaria.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.asimon.sefaria.data.local.SefariaDb
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SefariaDb's migrations against the schemas Room exports (app/schemas): a database made at the
 * old version and migrated has the same tables, columns and indexes as one made at the new
 * version, which is what Room checks when it opens it, and keeps its rows.
 */
class MigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun schema(version: Int): List<String> {
        val f = File("schemas/${SefariaDb::class.qualifiedName}/$version.json")
        val db = Json.parseToJsonElement(f.readText()).jsonObject.getValue("database").jsonObject
        return db.getValue("entities").jsonArray.flatMap { e ->
            val o = e.jsonObject
            val table = o.getValue("tableName").jsonPrimitive.content
            val sql = listOf(o.getValue("createSql").jsonPrimitive.content) +
                o["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
            sql.map { it.replace("\${TABLE_NAME}", table) }
        }
    }

    private fun create(version: Int): SQLiteConnection =
        BundledSQLiteDriver().open(tmp.newFile().path).also { c -> schema(version).forEach { c.execSQL(it) } }

    /** Every table's columns (name, type, not null, default, key) and indexes (name, unique, columns). */
    private fun shape(c: SQLiteConnection): List<String> {
        val out = ArrayList<String>()
        val tables = c.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { st ->
            buildList { while (st.step()) add(st.getText(0)) }
        }
        for (t in tables) {
            c.prepare("PRAGMA table_info(`$t`)").use { st ->
                while (st.step()) out += "$t.${st.getText(1)} ${st.getText(2)} notnull=${st.getLong(3)} default=${if (st.isNull(4)) null else st.getText(4)} pk=${st.getLong(5)}"
            }
            val indexes = c.prepare("PRAGMA index_list(`$t`)").use { st ->
                buildList { while (st.step()) add(st.getText(1) to st.getLong(2)) }
            }
            for ((name, unique) in indexes.sortedBy { it.first }) {
                val cols = c.prepare("PRAGMA index_info(`$name`)").use { st -> buildList { while (st.step()) add(st.getText(2)) } }
                out += "$t index $name unique=$unique $cols"
            }
        }
        return out
    }

    @Test
    fun version1To2() {
        val old = create(1)
        old.execSQL("INSERT INTO listed_file(file, kind, book, he, bytes, index_file, index_bytes) VALUES('a.sfb', 'book', 'Genesis', 'בראשית', 10, '', 0)")
        old.execSQL("INSERT INTO bookmark(book, ref, label, he_label, preview, created_at) VALUES('Genesis', 'Genesis.1.1', 'Genesis 1:1', 'בראשית א:א', '', 1)")
        SefariaDb.MIGRATION_1_2.migrate(old)
        assertEquals(shape(create(2)), shape(old))
        old.prepare("SELECT book, queued FROM listed_file").use { st ->
            st.step()
            assertEquals("Genesis", st.getText(0))
            assertEquals(0L, st.getLong(1))
        }
        old.prepare("SELECT COUNT(*) FROM bookmark").use { st -> st.step(); assertEquals(1L, st.getLong(0)) }
        old.close()
    }
}
