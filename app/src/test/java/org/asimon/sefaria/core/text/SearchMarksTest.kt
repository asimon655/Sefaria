package org.asimon.sefaria.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMarksTest {
    @Test
    fun wordsFromSnippetAndQuery() {
        // the bold forms (niqqud, prefix, final letter folded), then the query's own word
        assertEquals(
            listOf("ובראשית", "בראשית"),
            SearchMarks.words("… <b>וּבְרֵאשִׁ֖ית</b> ברא <b>בְּרֵאשִׁית</b> …", "בראשית"),
        )
        assertEquals(listOf("creation", "creat*"), SearchMarks.words("the <b>Creation,</b> of", "creat*"))
        // a phrase: each word
        assertEquals(listOf("in", "the", "beginning"), SearchMarks.words("", "\"in the beginning\""))
        // an unclosed <b> runs to the end
        assertEquals(listOf("ארצ"), SearchMarks.words("x <b>ארץ", "ארץ"))
    }

    @Test
    fun boldWordWithSeveralTokens() {
        // אל־הארץ is bold for הארץ: אל is not marked
        assertEquals(listOf("הארצ"), SearchMarks.words("ויבא <b>אל־הארץ</b>", "הארץ"))
        // a form that doesn't hold the query word: its long tokens only
        assertEquals(listOf("ארצות", "ארצ"), SearchMarks.words("<b>כל־ארצות</b>", "ארץ"))
    }

    @Test
    fun rangesInDisplayedText() {
        val m = SearchMarks.Matcher(listOf("בראשית", "ובראשית"))
        val text = "בְּרֵאשִׁ֖ית בָּרָ֣א, וּבְרֵאשִׁית: סוף"
        val r = m.ranges(text)
        assertEquals(listOf("בְּרֵאשִׁ֖ית", "וּבְרֵאשִׁית"), r.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun rangesSplitAtMaqafAndKeepInnerQuotes() {
        val text = "וַיָּבֹא אֶל־הָאָרֶץ. אמר רש\"י, \"שלום\""
        assertEquals(listOf("הָאָרֶץ"), cut(text, SearchMarks.Matcher(listOf("הארצ"))))
        // the gershayim inside רש"י is part of it; the quotes around "שלום" are not
        assertEquals(listOf("רש\"י", "שלום"), cut(text, SearchMarks.Matcher(listOf("רשי", "שלומ"))))
    }

    @Test
    fun rangesForPrefixAndLatin() {
        val text = "The Creator created créatures; recreate."
        assertEquals(listOf("Creator", "created", "créatures"), cut(text, SearchMarks.Matcher(listOf("creat*"))))
    }

    @Test
    fun nothingToMark() {
        assertTrue(SearchMarks.Matcher(emptyList()).ranges("anything").isEmpty())
        assertTrue(SearchMarks.Matcher(listOf("x")).ranges("").isEmpty())
    }

    private fun cut(text: String, m: SearchMarks.Matcher) = m.ranges(text).map { text.substring(it.first, it.last + 1) }
}
