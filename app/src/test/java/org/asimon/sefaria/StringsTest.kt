package org.asimon.sefaria

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Every interface string exists in both languages, with the same format arguments. */
class StringsTest {
    private fun strings(dir: String): Map<String, String> {
        val f = File("src/main/res/$dir/strings.xml") // unit tests run from the module directory
        return Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(f.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun args(s: String) = Regex("""%\d\$[sd]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun hebrewMatchesEnglish() {
        val en = strings("values")
        val he = strings("values-iw")
        assertEquals("missing or extra Hebrew strings", en.keys, he.keys)
        for ((k, v) in en) assertEquals("format arguments of $k", args(v), args(he.getValue(k)))
    }
}
