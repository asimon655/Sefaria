package org.asimon.sefaria.data.cache

import android.content.Context
import androidx.compose.ui.util.fastJoinToString
import dagger.hilt.android.qualifiers.ApplicationContext
import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.FileCache
import org.asimon.sefaria.data.FileCacheKv
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import java.io.File
import javax.inject.Inject
import javax.inject.Named

class PersistentCacheDataSource @Inject constructor(
    @ApplicationContext appContext: Context,
    @Named("cacheMaxSize") maxSize: Int = 1000,
) : CacheDataSource {
    private var tableOfContentFileCache = FileCache<List<Content>>(File(appContext.cacheDir, "tableofcontent.json"))
    override suspend fun putTableOfContent(tableOfContent: List<Content>) {
        tableOfContentFileCache.put(tableOfContent)
    }

    override suspend fun getTableOfContent(): List<Content>? {
        return tableOfContentFileCache.get()
    }

    private var booksFileCache = FileCache<Map<String, Book>>(File(appContext.cacheDir, "books.json"))
    override suspend fun putBooks(books: Map<String, Book>) {
        booksFileCache.put(books)
    }

    override suspend fun getBooks(): Map<String, Book>? {
        return booksFileCache.get()
    }


    private var cacheFileSchemasMap = FileCacheKv(maxSize, File(appContext.cacheDir, "schemas.csv"))
    override suspend fun putSchema(ref: String, schema: List<Schema>) {
        cacheFileSchemasMap.put(ref, schema.fastJoinToString("\n"))
    }

    override suspend fun getSchema(ref: String): List<Schema>? {
        return cacheFileSchemasMap.get(ref)?.split('\n')?.map {schema -> Schema(schema)}
    }

    val cacheFileTextMap = FileCacheKv(maxSize, File(appContext.cacheDir, "text.csv"))
    override suspend fun putText(ref: String, text: List<Line>) {
        cacheFileTextMap.put(ref, text.fastJoinToString("\n"))
    }

    override suspend fun getText(ref: String): List<Line>? {
        return cacheFileTextMap.get(ref)?.split('\n')?.map { line -> Line(line) }
    }


    fun sizeConstraints() {
        // check cacheFileText.length() against the max cache if exists
    }
}