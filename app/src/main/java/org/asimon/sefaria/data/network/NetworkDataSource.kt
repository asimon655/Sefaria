package org.asimon.sefaria.data.network

import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema

interface NetworkDataSource {
    suspend fun getTableOfContent(): List<Content>

    suspend fun getText(ref: String): List<Line>

    suspend fun getSchema(ref: String): List<Schema>
}