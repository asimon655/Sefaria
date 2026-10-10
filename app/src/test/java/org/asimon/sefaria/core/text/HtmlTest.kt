package org.asimon.sefaria.core.text

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.remote.TextDto
import org.asimon.sefaria.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {
    @Test
    fun styles() {
        val r = Html.parse("<big><strong>מֵאֵימָתַי</strong></big> קוֹרִין<br>x <i>y</i> <small>z</small>")
        assertEquals("מֵאֵימָתַי קוֹרִין\nx y z", r.plain)
        val styles = r.text.spanStyles
        assertTrue(styles.any { it.item.fontWeight == FontWeight.Bold && r.plain.substring(it.start, it.end) == "מֵאֵימָתַי" })
        assertTrue(styles.any { it.item.fontSize == 1.2.em && it.start == 0 })
        assertTrue(styles.any { it.item.fontStyle == FontStyle.Italic && r.plain.substring(it.start, it.end) == "y" })
        assertTrue(styles.any { it.item.fontSize == 0.8.em && r.plain.substring(it.start, it.end) == "z" })
    }

    @Test
    fun footnotes() {
        val r = Html.parse("""<sup class="footnote-marker">*</sup><i class="footnote"><b>When God began </b>Others “In the beginning.”</i> heaven and earth—""")
        assertEquals("* heaven and earth—", r.plain)
        assertEquals(listOf("When God began Others “In the beginning.”"), r.notes)
    }

    @Test
    fun junk() {
        // stray "<", unknown tags, entities, empty commentator markers, unclosed tags
        assertEquals("a < according b", Html.parse("a < according b").plain)
        assertEquals("x<y", Html.parse("x&lt;y").plain)
        assertEquals("AB", Html.parse("""A<i data-commentator="Bach" data-order="1.1"></i>B""").plain)
        assertEquals("text", Html.parse("<foo>text</foo>").plain)
        assertEquals("1<2 and 3>2", Html.parse("1<2 and 3>2").plain)
        assertEquals("bold", Html.parse("<b>bold").plain)
        assertEquals("a b", Html.parse("a&nbsp;b").plain.replace(' ', ' '))
        assertEquals("{פ}", Html.parse("""<span class="mam-spi-pe">{פ}</span>""").plain)
    }

    @Test
    fun stripping() {
        val s = "<big>בְּ</big>רֵאשִׁ֖ית"
        assertEquals("בראשית", Html.parse(s, stripNiqqud = true, stripCantillation = true).plain)
        assertEquals("בְּרֵאשִׁית", Html.parse(s, stripNiqqud = false, stripCantillation = true).plain)
    }

    @Test
    fun parsesEveryFixtureSegment() {
        for (name in listOf("text_Genesis.1.json", "text_Berakhot.2a.json", "text_Pesach_Haggadah,_Kadesh.json", "text_Rashi_on_Genesis.1.1.json")) {
            val t = SefariaJson.decodeFromString<TextDto>(fixture(name))
            for (s in t.sections) for (seg in s.he + s.en) {
                val r = Html.parse(seg)
                assertFalse("$name: $seg", r.plain.contains("<b>") || r.plain.contains("<i") || r.plain.contains("</"))
            }
        }
    }
}
