package org.asimon.sefaria.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Typed API results; the client never throws for HTTP or network failures. */
sealed interface ApiResult<out T> {
    /** [raw] is the response body, stored verbatim in the cache. */
    data class Ok<T>(val value: T, val raw: String, val etag: String?) : ApiResult<T>
    data object NotModified : ApiResult<Nothing>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
    /** A server error body (SERVER_SPEC §6.1), e.g. code = "ref_not_found". */
    data class Http(val status: Int, val code: String, val message: String) : ApiError
    data class Network(val cause: IOException) : ApiError
    data class BadResponse(val message: String) : ApiError
}

/**
 * The /v1 API client. [baseUrl] is read on every call so the server URL can change in
 * Settings without restarting. Conditional requests use the cached ETag (the build id).
 */
class Api(
    private val client: Call.Factory,
    private val baseUrl: suspend () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun manifest() = get("/v1/manifest", ManifestDto.serializer())

    /** The category tree as a SQLite file (/v1/toc.sqlite), written to [dest]; okhttp un-gzips it. */
    suspend fun tocDb(dest: File, etag: String? = null): ApiResult<File> = withContext(io) {
        val url = url("/v1/toc.sqlite", emptyList()) ?: return@withContext ApiResult.Failure(ApiError.BadResponse("bad server URL"))
        val request = Request.Builder().url(url).apply { if (etag != null) header("If-None-Match", etag) }.build()
        try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 304 -> ApiResult.NotModified
                    resp.isSuccessful -> {
                        dest.outputStream().use { resp.body.byteStream().copyTo(it) }
                        ApiResult.Ok(dest, "", resp.header("ETag"))
                    }
                    else -> ApiResult.Failure(httpError(resp.code, resp.body.string()))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e))
        }
    }

    suspend fun book(title: String, etag: String? = null) =
        get("/v1/books/" + segment(title), BookDto.serializer(), etag)

    /** A section in the book's default versions; always both languages so switching layout needs no request. */
    suspend fun text(ref: String, etag: String? = null) =
        get("/v1/text/" + segment(ref), TextDto.serializer(), etag, mapOf("lang" to "he,en"))

    /** Connections of a ref; [book] limits them to one book, [withText] inlines both languages. */
    suspend fun links(ref: String, withText: Boolean = false, book: String? = null, etag: String? = null) =
        get("/v1/links/" + segment(ref), LinksDto.serializer(), etag, buildMap {
            if (withText) {
                put("with_text", "1")
                put("lang", "he,en")
            }
            book?.let { put("book", it) }
        })

    /** The library of the server's build: book files, links file and their dictionary. */
    suspend fun library(etag: String? = null) = get("/v1/library", LibraryDto.serializer(), etag)

    /** Where a library file is downloaded from. */
    fun libraryPath(file: String) = "/v1/library/$file"

    suspend fun resolve(q: String) = get("/v1/resolve", ResolveDto.serializer(), query = mapOf("q" to q))

    /** The query as a ref, then books by title (SERVER_SPEC §6.6). */
    suspend fun complete(q: String, limit: Int = 6) =
        get("/v1/complete", CompleteDto.serializer(), query = mapOf("q" to q, "limit" to limit.toString()))

    /** One page of full-text hits in reading order; pass the previous page's [SearchDto.next] as [cursor]. */
    suspend fun search(q: String, filter: SearchFilter = SearchFilter(), cursor: String? = null, limit: Int = 20) =
        get("/v1/search", SearchDto.serializer(), params = buildList {
            add("q" to q)
            add("limit" to limit.toString())
            filter.lang?.let { add("lang" to it) }
            filter.books.forEach { add("book" to it) }
            filter.categories.forEach { add("category" to it) }
            if (!filter.expand) add("expand" to "0")
            if (filter.allForms) add("forms" to "all")
            filter.drop.sorted().forEach { add("drop" to it) }
            filter.order?.let { add("order" to it) }
            cursor?.let { add("cursor" to it) }
        })

    /** The day's learning; [date] is the reader's local date (YYYY-MM-DD). */
    suspend fun calendar(date: String, diaspora: Boolean, custom: String, etag: String? = null) =
        get("/v1/calendar", CalendarDto.serializer(), etag, query = mapOf(
            "date" to date, "diaspora" to if (diaspora) "1" else "0", "custom" to custom,
        ))

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        etag: String? = null,
        query: Map<String, String> = emptyMap(),
        params: List<Pair<String, String>> = query.toList(), // repeated keys allowed
    ): ApiResult<T> = withContext(io) {
        val url = url(path, params) ?: return@withContext ApiResult.Failure(ApiError.BadResponse("bad server URL ${baseUrl()}"))
        val request = Request.Builder().url(url).apply {
            if (etag != null) header("If-None-Match", etag)
        }.build()
        try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                when {
                    resp.code == 304 -> ApiResult.NotModified
                    resp.isSuccessful -> ApiResult.Ok(SefariaJson.decodeFromString(serializer, body), body, resp.header("ETag"))
                    else -> ApiResult.Failure(httpError(resp.code, body))
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Network(e))
        } catch (e: SerializationException) {
            ApiResult.Failure(ApiError.BadResponse(e.message ?: "invalid JSON"))
        } catch (e: IllegalArgumentException) { // kotlinx.serialization reports some decode errors this way
            ApiResult.Failure(ApiError.BadResponse(e.message ?: "invalid JSON"))
        }
    }

    private suspend fun url(path: String, params: List<Pair<String, String>>) =
        (baseUrl().trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build()

    private fun httpError(status: Int, body: String): ApiError.Http = try {
        val e = SefariaJson.decodeFromString(ErrorDto.serializer(), body).error
        ApiError.Http(status, e.code, e.message)
    } catch (_: Exception) {
        ApiError.Http(status, "", "HTTP $status")
    }

    /** A ref or title as one path segment; okhttp escapes it when the URL is parsed. */
    private fun segment(s: String) = s.replace(' ', '_').replace("/", "%2F").replace("?", "%3F").replace("#", "%23")
}

/** Full-text search filters; null means any. [category] is a path of English names joined by "/". */
/**
 * What a search covers and how it matches (SERVER_SPEC §6.6). The scope is the union of
 * [books] and [categories] ("Tanakh/Torah"); both empty means the whole library. [expand]
 * false matches exact words only, [allForms] adds the other forms of the words' headwords,
 * [drop] leaves out forms the user turned off, and [order] "library" asks for the two-tier
 * library order the phone uses offline instead of the server's ranking.
 */
data class SearchFilter(
    val lang: String? = null,
    val books: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val expand: Boolean = true,
    val allForms: Boolean = false,
    val drop: Set<String> = emptySet(),
    val order: String? = null,
) {
    val scoped get() = books.isNotEmpty() || categories.isNotEmpty()
}

