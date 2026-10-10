package org.asimon.sefaria.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.remote.Api
import org.asimon.sefaria.data.remote.ApiError
import org.asimon.sefaria.data.remote.ApiResult
import org.asimon.sefaria.data.remote.VersionSelection
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
        val r = api.text("Genesis.1", VersionSelection(he = 1))
        assertTrue(r is ApiResult.Ok)
        r as ApiResult.Ok
        assertEquals("\"b1\"", r.etag)
        assertEquals(31, r.value.sections.single().he.size)
        val req = server.takeRequest()
        assertEquals("/v1/text/Genesis.1?lang=he%2Cen&he=1", req.url.encodedPath + "?" + req.url.encodedQuery)
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
}
