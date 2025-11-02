package org.asimon.sefaria.data.repositories

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import org.asimon.sefaria.data.cache.CacheDataSource
import org.asimon.sefaria.data.network.NetworkDataSource
import org.asimon.sefaria.data.traverseTableOfContent
import javax.inject.Inject

class NetworkCacheDataRepository @Inject constructor(
    private val networkDataSource: NetworkDataSource,
    private val cacheDataSource: CacheDataSource,
): DataRepository {
    override fun getTableOfContent(): Flow<List<Content>> = flow {
        cacheDataSource.getTableOfContent()?.let {
            emit(it)
            return@flow
        }
        networkDataSource.getTableOfContent().let {
            coroutineScope { cacheDataSource.putTableOfContent(it) }
            emit(it)
        }
    }

    override fun getBook(ref: String): Flow<Book> = flow {
        cacheDataSource.getBooks()?.let { books ->
            val book = checkNotNull(books[ref]) { "couln't find ref: $ref in Books map from cache" }
            emit(book)
            return@flow
        }
        getTableOfContent().first().let { tableOfContent ->
            val books = traverseTableOfContent(tableOfContent)
            val book = books[ref]
            checkNotNull(book) { "failed to find book with ref: $ref in toc: $tableOfContent" }
            coroutineScope { cacheDataSource.putBooks(books) }
            emit(book)
        }
    }

    override fun getText(ref: String): Flow<List<Line>> = flow {
        cacheDataSource.getText(ref)?.let {
            emit(it)
            return@flow
        }
        networkDataSource.getText(ref).let {
            coroutineScope { cacheDataSource.putText(ref, it) }
            emit(it)
        }
    }

    override fun getSchema(ref: String): Flow<List<Schema>> = flow {
        cacheDataSource.getSchema(ref)?.let {
            emit(it)
            return@flow
        }
        networkDataSource.getSchema(ref).let {
            coroutineScope { cacheDataSource.putSchema(ref, it) }
            emit(it)
        }
    }
}