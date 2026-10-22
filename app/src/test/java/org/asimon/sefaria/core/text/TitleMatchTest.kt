package org.asimon.sefaria.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleMatchTest {
    // (English, Hebrew) in TOC order
    private val books = listOf(
        "Genesis" to "בראשית",
        "Exodus" to "שמות",
        "Genesis Rabbah" to "בראשית רבה",
        "Rashi on Genesis" to "רש\"י על בראשית",
        "Berakhot" to "ברכות",
        "Shulchan Arukh, Orach Chayim" to "שולחן ערוך, אורח חיים",
        "Pesach Haggadah" to "הגדה של פסח",
        "Mishneh Torah, Foundations of the Torah" to "משנה תורה, הלכות יסודי התורה",
    )

    private fun rank(q: String, limit: Int = 10) = TitleMatch.rank(q, books, limit) { listOf(it.first, it.second) }.map { it.first }

    @Test
    fun startOfTheNameFirstThenTocOrder() {
        assertEquals(listOf("Genesis", "Genesis Rabbah", "Rashi on Genesis"), rank("gen"))
        assertEquals("Genesis", rank("Genesis").first())
    }

    @Test
    fun aLaterWord() {
        assertEquals(listOf("Shulchan Arukh, Orach Chayim"), rank("orach"))
        assertEquals(listOf("Mishneh Torah, Foundations of the Torah"), rank("foundations"))
    }

    @Test
    fun wordsInAnyOrder() = assertEquals(listOf("Shulchan Arukh, Orach Chayim"), rank("arukh shulchan"))

    @Test
    fun insideAWord() = assertEquals(listOf("Pesach Haggadah"), rank("aggad"))

    @Test
    fun typos() {
        assertEquals(listOf("Berakhot"), rank("berachot"))
        assertEquals(listOf("Berakhot"), rank("brakhot"))
        assertEquals("Shulchan Arukh, Orach Chayim", rank("shulhan aruch").first())
        assertEquals("Genesis", rank("gensis").first())
    }

    @Test
    fun shortWordsMustMatchExactly() {
        assertEquals(emptyList<String>(), rank("gem")) // not Genesis: too short to guess
        assertEquals(emptyList<String>(), rank("zz"))
    }

    @Test
    fun hebrewWithoutQuotesOrFinalLetters() {
        assertEquals(listOf("Rashi on Genesis"), rank("רשי"))
        assertEquals(listOf("Rashi on Genesis"), rank("רש\"י"))
        assertEquals("Shulchan Arukh, Orach Chayim", rank("שולחן ערוכ").first())
        assertEquals("Genesis", rank("בראשית").first())
    }

    @Test
    fun hebrewTypo() = assertEquals("Shulchan Arukh, Orach Chayim", rank("שלחן ערוך").first())

    @Test
    fun limit() = assertEquals(listOf("Genesis"), rank("gen", limit = 1))
}
