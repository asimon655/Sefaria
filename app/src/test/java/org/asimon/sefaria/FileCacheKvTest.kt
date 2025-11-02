package org.asimon.sefaria

import junit.framework.TestCase.assertEquals
import org.asimon.sefaria.data.FileCacheKv
import org.asimon.sefaria.data.Line
import org.junit.Test
import java.io.File
import java.io.UTFDataFormatException
import kotlin.io.path.createTempFile
import kotlin.math.abs

class FileCacheKvTest {

    // size 1KB
    val size = 100_000_000
    val cache = FileCacheKv(size, createTempFile("testCache.csv").toFile())

    @Test
    fun readWrite() {
        val line = Line("Genesis.1.1", "בראשית ברא אלוהים את השמיים ואת הארץ")
        val line2 = Line("Genesis.1.2", "והארץ הייתה תוהו ובוהו חוך על פני תהום ורוח אלוהים מרחפת על פני המים")
        cache.put(line.ref, line.toString())
        cache.put(line2.ref, line2.toString())
        val res = cache.get(line.ref)?.split('\n')?.map(::Line)
        val res2 = cache.get(line2.ref)?.split('\n')?.map(::Line)
        assertEquals(line.ref, res?.first()?.ref)
        assertEquals(line.text, res?.first()?.text)
        assertEquals(line2.ref, res2?.first()?.ref)
        assertEquals(line2.text, res2?.first()?.text)
    }

    @Test
    fun checkHash() {
        val keyPut = "Genesis.1.1"
        val keyGet = "Genesis.1.1"
        assertEquals(keyPut, keyGet)
        val hashPut = abs(keyPut.hashCode() % size)
        val hashGet = abs(keyGet.hashCode() % size)
        assertEquals(hashPut, hashGet)
    }

    @Test
    fun shrink() {

        val combinedOutput = File("/Users/asimon/Code/sefaria_server/combined_output_old.txt")
        var counter = 0
        for (line in combinedOutput.bufferedReader().lines()) {
            try {
                cache.put(line.substringBefore('@'), line)
                if (counter++ > 10_000_000) break
            } catch (e: UTFDataFormatException) {

            }
        }
    }
}