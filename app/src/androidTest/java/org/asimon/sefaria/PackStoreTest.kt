package org.asimon.sefaria

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.asimon.sefaria.core.text.SearchText
import org.asimon.sefaria.data.local.PackStore
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A real pack written by sefaria-pack (Pesach Haggadah) installed, read and deleted. */
@RunWith(AndroidJUnit4::class)
class PackStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "packs-test").apply { deleteRecursively() }

    private fun download(): File {
        val f = File(dir.apply { mkdirs() }, "x.part")
        InstrumentationRegistry.getInstrumentation().context.assets.open("pack_haggadah.sqlite").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    @Test
    fun installReadDelete() = runBlocking {
        val store = PackStore(dir)
        // a pack of another book is refused and deleted
        val wrong = download()
        assertNotNull(store.install("Genesis", wrong))
        assertFalse(wrong.exists())
        assertTrue(store.installed.value.isEmpty())

        assertNull(store.install("Pesach Haggadah", download()))
        val p = store.installed.value["Pesach Haggadah"]!!
        assertEquals("הגדה של פסח", p.he)
        assertEquals(512000L, p.bytes)
        assertTrue(store.fileFor("Pesach Haggadah").exists())

        val json = store.get("Pesach Haggadah", "text:Pesach_Haggadah,_Kadesh|he=d,en=d")!!
        val t = SefariaJson.decodeFromString(TextDto.serializer(), json)
        assertEquals("Pesach_Haggadah,_Kadesh", t.sections.single().ref)
        assertTrue(t.sections.single().he.isNotEmpty())
        assertNotNull(store.get("Pesach Haggadah", "book:Pesach Haggadah"))
        assertNotNull(store.get("Pesach Haggadah", "links:Pesach_Haggadah,_Kadesh.1"))
        assertNull(store.get("Pesach Haggadah", "text:nope"))
        assertNull(store.get("Genesis", "book:Genesis"))

        // offline search: the bundled SQLite has FTS5, and the pack's index pages in reading order
        val expr = SearchText.expr(SearchText.parse("wine"))
        val first = store.search("Pesach Haggadah", "en", expr, -1, 2)
        assertEquals(2, first.size)
        assertTrue(first[0].slot < first[1].slot && first[0].ref.startsWith("Pesach_Haggadah"))
        assertTrue(first[0].human.startsWith("Pesach Haggadah") && first[0].heRef.startsWith("הגדה של פסח"))
        val rest = store.search("Pesach Haggadah", "en", expr, first[1].slot, 100)
        assertTrue(rest.all { it.slot > first[1].slot })
        assertTrue(store.search("Pesach Haggadah", "he", SearchText.expr(SearchText.parse("קדש")), -1, 5).isNotEmpty())
        assertTrue(store.search("Genesis", "he", expr, -1, 5).isEmpty())

        // a new store (app restart) finds the installed pack
        val again = PackStore(dir)
        assertNotNull(again.get("Pesach Haggadah", "book:Pesach Haggadah"))
        again.delete("Pesach Haggadah")
        assertFalse(store.fileFor("Pesach Haggadah").exists())
        assertTrue(again.installed.value.isEmpty())
    }
}
