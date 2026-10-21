package org.asimon.sefaria.data

import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDtoTest {
    @Test
    fun emptyAltStructIsDropped() {
        val json = """{"title":"Berakhot","schema":{},"alt_structs":{"Daf Yomi":null,"Empty":[],"Venice":[{"en":"Chapter 1"}]}}"""
        val b = SefariaJson.decodeFromString(BookDto.serializer(), json)
        assertEquals(listOf("Venice"), b.altStructs.keys.toList())
        assertEquals("Chapter 1", b.altStructs["Venice"]!!.first().en)
    }
}
