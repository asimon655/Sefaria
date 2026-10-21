package org.asimon.sefaria.data

import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** kotlinx-collections-immutable 0.4.0 failed an assertion here, crashing "download all" on resume. */
class PersistentSetTest {
    @Test
    fun removingAbsentTitlesFromABigSet() {
        val pending = persistentSetOf<String>().addAll((0 until 3000).map { "Book $it" })
        assertEquals(pending, pending.removeAll((0 until 100).map { "Other $it" }.toSet()))
    }
}
