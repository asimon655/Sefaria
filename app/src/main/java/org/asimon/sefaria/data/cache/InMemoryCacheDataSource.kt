package org.asimon.sefaria.data.cache

import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import javax.inject.Inject

class InMemoryCacheDataSource @Inject constructor(): CacheDataSource {
    private var tableOfContent: List<Content>? = null
    override suspend fun putTableOfContent(tableOfContent: List<Content>) {
        this.tableOfContent = tableOfContent
    }

    override suspend fun getTableOfContent(): List<Content>? {
        return tableOfContent
    }

    private var books: Map<String, Book>? = null
    override suspend fun putBooks(books: Map<String, Book>) {
        this.books = books
    }

    override suspend fun getBooks(): Map<String, Book>? {
        return books
    }

    private var texts = HashMap<String, List<Line>>()
    override suspend fun putText(ref: String, text: List<Line>) {
        texts.put(ref, text)
    }

    override suspend fun getText(ref: String): List<Line>? {
        return texts[ref]
    }

    private var schemas = HashMap<String, List<Schema>>()
    override suspend fun putSchema(ref: String, schema: List<Schema>) {
        schemas.put(ref, schema)
    }

    override suspend fun getSchema(ref: String): List<Schema>? {
        return schemas[ref]
    }
}