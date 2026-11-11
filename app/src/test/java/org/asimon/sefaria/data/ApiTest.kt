package org.asimon.sefaria.data

import org.asimon.sefaria.data.toAppError
import org.asimon.sefaria.data.AppError
import org.junit.Assert.assertFalse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.fixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiTest {
    private val server = MockWebServer()
    private lateinit var api: Api

    @Before
    fun setUp() {
        server.start()
        api = Api(OkHttpClient(), { server.url("/").toString() }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, etag: String? = null) = MockResponse.Builder()
        .code(code).body(body).addHeader("Content-Type", "application/json").apply {
            if (etag != null) addHeader("ETag", etag)
        }.build()

    @Test
    fun okWithEtagAndQuery() = runTest {
        server.enqueue(json(fixture("text_Genesis.1.json"), etag = "\"b1\""))
        val r = api.text("Genesis.1")
        assertTrue(r is ApiResult.Ok)
        r as ApiResult.Ok
        assertEquals("\"b1\"", r.etag)
        assertEquals(31, r.value.sections.single().he.size)
        val req = server.takeRequest()
        assertEquals("/v1/text/Genesis.1?lang=he%2Cen", req.url.encodedPath + "?" + req.url.encodedQuery)
    }

    @Test
    fun conditionalRequest() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(ApiResult.NotModified, api.book("Genesis", etag = "\"b1\""))
        val req = server.takeRequest()
        assertEquals("\"b1\"", req.headers["If-None-Match"])
        assertEquals("/v1/books/Genesis", req.url.encodedPath)
    }

    @Test
    fun hebrewAndSpacesInPath() = runTest {
        server.enqueue(json(fixture("book_Pesach_Haggadah.json")))
        api.book("Pesach Haggadah")
        assertEquals("/v1/books/Pesach_Haggadah", server.takeRequest().url.encodedPath)
    }

    @Test
    fun serverErrorIsTyped() = runTest {
        server.enqueue(json(fixture("error_ref_not_found.json"), code = 404))
        val r = api.text("Genesiss.1")
        val e = (r as ApiResult.Failure).error as ApiError.Http
        assertEquals(404, e.status)
        assertEquals("ref_not_found", e.code)
    }

    @Test
    fun badJsonAndNetworkAreTyped() = runTest {
        server.enqueue(json("{not json"))
        assertTrue((api.manifest() as ApiResult.Failure).error is ApiError.BadResponse)
        server.close()
        assertTrue((api.manifest() as ApiResult.Failure).error is ApiError.Network)
    }

    @Test
    fun retryAfterIsWaitedOutOnce() = runTest {
        // a busy server says when to come back: a short wait is taken, once
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "0").body("""{"error":{"code":"rate_limited","message":"too many requests"}}""").build())
        server.enqueue(json("""{"build_id":"b1","library":"abc"}"""))
        val ok = api.manifest() as ApiResult.Ok
        assertEquals("b1", ok.value.buildId)
        assertEquals("abc", ok.value.library)
        assertEquals(2, server.requestCount)
        // twice busy: the error, with its Retry-After, for the caller to decide
        // (Retry-After: 1, not 0: OkHttp itself retries a 503 that says 0)
        repeat(2) { server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "1").body("""{"error":{"code":"search_busy","message":"busy"}}""").build()) }
        val e = (api.search("x") as ApiResult.Failure).error
        assertEquals(ApiError.Http(503, "search_busy", "busy", retryAfter = 1), e)
        assertEquals(AppError.Busy(1), e.toAppError())
        assertEquals(4, server.requestCount)
        // a long Retry-After is not waited out here
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "60").body("{}").build())
        assertEquals(AppError.Busy(60), (api.manifest() as ApiResult.Failure).error.toAppError())
        assertEquals(5, server.requestCount)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        // the server never answers: only the cancel ends the call
        server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).body("{}").build())
        val started = System.nanoTime()
        val job = launch(Dispatchers.Default) { api.manifest() }
        server.takeRequest() // the request is on the wire
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue("cancel took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
    }

    @Test
    fun errorsSayWhatHappensNext() {
        fun http(status: Int, code: String) = ApiError.Http(status, code, "m").toAppError()
        assertEquals(AppError.NotFound("m"), http(404, "ref_not_found"))
        assertEquals(AppError.NotFound("m"), http(400, "bad_ref"))
        assertEquals(AppError.Rejected("m"), http(400, "bad_param"))
        assertEquals(AppError.Busy(1), http(429, "rate_limited"))
        assertEquals(AppError.SearchTimeout, http(503, "search_failed"))
        assertEquals(AppError.Server(500, "m"), http(500, "internal"))
        // transient: waiting may cure it, so a cached or downloaded copy answers and a download stays queued
        for (e in listOf(AppError.Network, AppError.Busy(1), AppError.SearchTimeout, AppError.Server(503, ""))) assertTrue("$e", e.transient)
        for (e in listOf(AppError.NotFound(""), AppError.Rejected(""), AppError.BadResponse(""), AppError.Storage(1, 0), AppError.NoText)) assertFalse("$e", e.transient)
    }
}
