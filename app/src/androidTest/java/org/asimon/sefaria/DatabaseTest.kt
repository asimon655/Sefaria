package org.asimon.sefaria

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.asimon.sefaria.data.local.CacheEntry
import org.asimon.sefaria.data.local.CacheDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    /** Phase 0 spike (APP_SPEC §8): the bundled SQLite driver must have FTS5 for offline search. */
    @Test
    fun bundledSqliteHasFts5() {
        val conn = BundledSQLiteDriver().open(":memory:")
        conn.execSQL("CREATE VIRTUAL TABLE t USING fts5(body)")
        conn.execSQL("INSERT INTO t VALUES ('בראשית ברא אלהים')")
        conn.prepare("SELECT count(*) FROM t WHERE t MATCH 'ברא'").use {
            it.step()
            assertEquals(1L, it.getLong(0))
        }
        conn.close()
    }

    @Test
    fun cacheTrimDropsLeastRecentlyUsed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, CacheDb::class.java)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val dao = db.cache()
        dao.put(CacheEntry("old", "x".repeat(100), null, 0, lastAccess = 1))
        dao.put(CacheEntry("mid", "x".repeat(100), null, 0, lastAccess = 2))
        dao.put(CacheEntry("new", "x".repeat(100), null, 0, lastAccess = 3))
        dao.trim(keepBytes = 250)
        assertNull(dao.get("old"))
        assertNotNull(dao.get("mid"))
        assertNotNull(dao.get("new"))
        assertEquals(200L, dao.bytes())
        db.close()
    }
}
