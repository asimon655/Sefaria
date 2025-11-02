package org.asimon.sefaria.data.download

import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema

interface DownloadDataSource {
    // put complete books
    suspend fun putText(ref: String, text: List<Line>)
    suspend fun getText(ref: String): List<Line>?
    suspend fun delText(ref: String)

    // put complete books schemas
    suspend fun putSchema(ref: String, schema: List<Schema>)
    suspend fun getSchema(ref: String): List<Schema>?
    suspend fun delSchema(ref: String)
}