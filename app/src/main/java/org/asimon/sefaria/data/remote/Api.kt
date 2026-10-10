package org.asimon.sefaria.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val client: OkHttpClient,
    private val baseUrl: suspend () -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun manifest() = get("/v1/manifest", ManifestDto.serializer())

    suspend fun toc(etag: String? = null) = get("/v1/toc", TocDto.serializer(), etag)

    suspend fun book(title: String, etag: String? = null) =
        get("/v1/books/" + segment(title), BookDto.serializer(), etag)

    /** Always fetches both languages so switching layout needs no request. */
    suspend fun text(ref: String, versions: VersionSelection = VersionSelection(), etag: String? = null) =
        get("/v1/text/" + segment(ref), TextDto.serializer(), etag, buildMap {
            put("lang", "he,en")
            versions.he?.let { put("he", it.toString()) }
            versions.en?.let { put("en", it.toString()) }
        })

    suspend fun resolve(q: String) = get("/v1/resolve", ResolveDto.serializer(), query = mapOf("q" to q))

    private suspend fun <T> get(
        path: String,
        serializer: KSerializer<T>,
        etag: String? = null,
        query: Map<String, String> = emptyMap(),
    ): ApiResult<T> = withContext(io) {
        val base = baseUrl()
        val url = (base.trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: return@withContext ApiResult.Failure(ApiError.BadResponse("bad server URL $base"))
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

    private fun httpError(status: Int, body: String): ApiError.Http = try {
        val e = SefariaJson.decodeFromString(ErrorDto.serializer(), body).error
        ApiError.Http(status, e.code, e.message)
    } catch (_: Exception) {
        ApiError.Http(status, "", "HTTP $status")
    }

    /** A ref or title as one path segment; okhttp escapes it when the URL is parsed. */
    private fun segment(s: String) = s.replace(' ', '_').replace("/", "%2F").replace("?", "%3F").replace("#", "%23")
}

/** Version ids per language; null means the book's default version. */
data class VersionSelection(val he: Int? = null, val en: Int? = null) {
    /** Cache key part, e.g. "he=1,en=d". */
    val key get() = "he=${he ?: "d"},en=${en ?: "d"}"
}
