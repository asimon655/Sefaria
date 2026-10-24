package org.asimon.sefaria.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.asimon.sefaria.core.ref.Book
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.BookDto
import org.asimon.sefaria.data.remote.SefariaJson
import org.asimon.sefaria.data.Load
import org.asimon.sefaria.data.datasource.CachedFetcher
import org.asimon.sefaria.data.map

/**
 * Books' schemas, versions and metadata (/v1/books), from the downloaded library or the response
 * cache ([CachedFetcher]); decoded books stay in memory in a small LRU.
 */
class BookRepository(private val api: Api, private val fetcher: CachedFetcher) {
    private val books = object : LinkedHashMap<String, Book>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?) = size > 24
    }
    private val lock = Mutex()

    /**
     * A book's schema, versions and metadata; decoded books stay in memory (small LRU). [remember]
     * false leaves that LRU as it is (search decodes many books once: it keeps its own).
     */
    suspend fun book(title: String, remember: Boolean = true): Load<Book> {
        val key = title.replace('_', ' ')
        lock.withLock { books[key] }?.let { return Load.Ok(it) }
        val r = fetcher.get("book:$key", { decodeBook(it) }, book = key) { etag -> api.book(key, etag) }
            .let { it.map(::Book) }
        if (r is Load.Ok && remember) lock.withLock { books[key] = r.value }
        return r
    }

    private fun decodeBook(json: String) = SefariaJson.decodeFromString(BookDto.serializer(), json)
}
