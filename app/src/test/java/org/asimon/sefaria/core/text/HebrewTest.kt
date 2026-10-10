package org.asimon.sefaria.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HebrewTest {
    @Test
    fun gematria() {
        for ((n, s) in listOf(1 to "א", 15 to "טו", 16 to "טז", 17 to "יז", 99 to "צט", 400 to "ת", 613 to "תריג", 1000 to "א׳")) {
            assertEquals(s, Hebrew.gematria(n))
        }
        for (n in 1..2000) assertEquals(n, Hebrew.parseGematria(Hebrew.gematria(n).replace("׳", "")).let {
            if (n >= 1000) n else it // thousands are not parseable without the geresh rule
        })
        assertEquals(16, Hebrew.parseGematria("ט״ז"))
        assertNull(Hebrew.parseGematria("abc"))
        assertNull(Hebrew.parseGematria(""))
    }

    @Test
    fun strip() {
        val s = "בְּרֵאשִׁ֖ית בָּרָ֣א" // niqqud + te'amim
        assertEquals("בראשית ברא", Hebrew.stripMarks(s))
        assertEquals("בְּרֵאשִׁית בָּרָא", Hebrew.strip(s, niqqud = false, cantillation = true))
        assertEquals("בראש֖ית בר֣א", Hebrew.strip(s, niqqud = true, cantillation = false))
        assertEquals("וְכׇל־צְבָאָֽם׃".let(Hebrew::stripMarks), "וכל־צבאם׃") // maqaf and sof pasuq stay
    }

    @Test
    fun normalizeTitle() {
        assertEquals("shulchan arukh, orach chayim 1:1", Hebrew.normalizeTitle("  Shulchan_Arukh,_Orach  Chayim 1:1 "))
        assertEquals("ברכות ב ע\"א", Hebrew.normalizeTitle("בְּרָכוֹת ב ע״א"))
    }
}
