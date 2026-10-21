package org.asimon.sefaria.core.text

import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs the server's shared matcher vectors (server/internal/search/testdata/match.tsv, forms.tsv). */
class SearchMatchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun vectors(forms: Forms) {
        val rows = fixture("match.tsv").lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(rows.size >= 15)
        for (row in rows) {
            val (query, options, segment, want) = row.split('\t')
            val q = SearchMatch.expand(SearchText.parse(query), forms, Expansion.parse(options))
            val got = SearchMatch.match(SearchMatch.tokens(segment), q)
                ?.let { h -> h.kind.wire + " " + h.clauses.joinToString(";") { it.pos.first().toString() } } ?: "-"
            assertEquals("$query [$options] on $segment", want, got)
        }
    }

    @Test
    fun matchVectors() = vectors(TsvForms(fixture("forms.tsv")))

    @Test
    fun matchVectorsFromFile() {
        val f = File(tmp.root, "forms.tsv").apply { writeText(fixture("forms.tsv")) }
        vectors(FormsFile(f))
    }

    @Test
    fun formsFileLookups() {
        val f = FormsFile(File(tmp.root, "forms.tsv").apply { writeText(fixture("forms.tsv")) })
        assertEquals(FormEntry(listOf("בשבת", "השבת"), listOf("שבת")), f.entry("שבת"))
        assertEquals(listOf("אומר", "אמר", "ואמר", "ויאמר"), f.group("אמר"))
        assertEquals(null, f.entry("שב")) // a prefix of a key is not the key
        assertEquals(null, f.entry("תתת")) // after the last line
        assertEquals(null, f.entry("א")) // before the first word line
    }

    @Test
    fun snippetHighlightsForms() {
        val q = SearchMatch.expand(SearchText.parse("שבת"), TsvForms(fixture("forms.tsv")))
        assertEquals("זכור את יום <b>הַשַּׁבָּת</b> לקדשו", SearchMatch.snippet("זכור את יום הַשַּׁבָּת לקדשו", q))
    }
}
