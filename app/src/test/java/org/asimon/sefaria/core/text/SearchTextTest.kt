package org.asimon.sefaria.core.text

import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the server's shared normaliser vectors (server/internal/search/testdata). */
class SearchTextTest {
    @Test
    fun normalizeVectors() {
        val rows = fixture("normalize.tsv").lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val (input, want) = row.split('\t', limit = 2)
            assertEquals("normalize($input)", want, SearchText.normalize(input))
        }
    }

    @Test
    fun parse() {
        mapOf(
            "בראשית ברא" to "\"בראשית\" AND \"ברא\"",
            "\"בראשית ברא\" אלהים" to "\"בראשית ברא\" AND \"אלהימ\"",
            "רש\"י" to "\"רשי\"",
            "\"אמר רש\"י\"" to "\"אמר רשי\"",
            "אל־הארץ" to "\"אל הארצ\"",
            "creat*" to "\"creat\"*",
            "a*" to "\"a\"",
            "\"in the begin*\"" to "\"in the begin\"*",
            "  !! " to "",
            "\"unclosed phrase" to "\"unclosed phrase\"",
            "God's \"own words\" x" to "\"gods\" AND \"own words\" AND \"x\"",
        ).forEach { (q, want) -> assertEquals(q, want, SearchText.expr(SearchText.parse(q))) }
    }

    @Test
    fun snippet() {
        val terms = SearchText.parse("ברא creat*")
        assertEquals("בְּרֵאשִׁ֖ית <b>בָּרָ֣א</b> אֱלֹהִ֑ים", SearchText.snippet("בְּרֵאשִׁ֖ית <big>בָּרָ֣א</big> אֱלֹהִ֑ים", terms))
        assertEquals("God <b>created</b> & ‹x›", SearchText.snippet("God <i>created</i> &amp; &lt;x&gt;", terms))
        val long = "a ".repeat(100) + "created " + "b ".repeat(100)
        val s = SearchText.snippet(long, terms)
        assertTrue(s, s.startsWith("… a") && s.endsWith("b …") && "<b>created</b>" in s && s.split(' ').size == SearchText.SNIPPET_WORDS + 2)
    }
}
