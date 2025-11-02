package org.asimon.sefaria.data.cache

import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema

interface CacheDataSource {
    suspend fun putTableOfContent(tableOfContent: List<Content>)
    suspend fun getTableOfContent(): List<Content>?

    suspend fun putBooks(books: Map<String, Book>)
    suspend fun getBooks(): Map<String, Book>?

    suspend fun putText(ref: String, text: List<Line>)
    suspend fun getText(ref: String): List<Line>?

    suspend fun putSchema(ref: String, schema: List<Schema>)
    suspend fun getSchema(ref: String): List<Schema>?
}