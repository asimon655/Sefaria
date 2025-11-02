package org.asimon.sefaria.data.repositories

import kotlinx.coroutines.flow.Flow
import org.asimon.sefaria.data.Book
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema

interface DataRepository {
    fun getTableOfContent(): Flow<List<Content>>
    fun getBook(ref: String): Flow<Book>
    fun getText(ref: String): Flow<List<Line>>
    fun getSchema(ref: String): Flow<List<Schema>>
}