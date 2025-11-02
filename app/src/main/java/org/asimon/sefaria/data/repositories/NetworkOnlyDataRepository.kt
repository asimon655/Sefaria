package org.asimon.sefaria.data.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import org.asimon.sefaria.data.network.NetworkDataSource
import org.asimon.sefaria.data.traverseTableOfContent
import javax.inject.Inject

class NetworkOnlyDataRepository @Inject constructor(
    private val networkDataSource: NetworkDataSource
): DataRepository {
    override fun getTableOfContent(): Flow<List<Content>> = flow {
        emit(networkDataSource.getTableOfContent())
    }

    override fun getBook(ref: String): Flow<Book> = flow {
        val tableOfContent = networkDataSource.getTableOfContent()
        val bookMap = traverseTableOfContent(tableOfContent)
        val book = bookMap[ref]
        checkNotNull(book) { "failed to find book with ref: $ref in toc: $tableOfContent" }
        emit(book)
    }

    override fun getText(ref: String): Flow<List<Line>> = flow {
        emit(networkDataSource.getText(ref))
    }

    override fun getSchema(ref: String): Flow<List<Schema>> = flow {
        emit(networkDataSource.getSchema(ref))
    }
}