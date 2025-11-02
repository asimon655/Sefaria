package org.asimon.sefaria.data.network

import androidx.core.text.isDigitsOnly
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.asimon.sefaria.data.Content
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import org.asimon.sefaria.data.SchemaInt
import org.asimon.sefaria.data.SchemaString
import org.asimon.sefaria.data.await
import javax.inject.Inject

class OkHttpNetworkDataSource @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher,
): NetworkDataSource {

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun getTableOfContent(): List<Content> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("http://localhost:8080/tableofcontent")
            .build()

        val result = okHttpClient.newCall(request).execute()

        if (result.code == 404) throw Error("error fetching tableofcontent")

        Json.decodeFromStream(result.body.byteStream())
    }

    override suspend fun getText(ref: String): List<Line> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("http://localhost:8080/$ref")
            .build()

        val result = okHttpClient.newCall(request).execute()

        if (result.code == 404) throw Error("error fetching $ref")

        result.body.string().lines().mapNotNull { line ->
            if (line.trim().isEmpty()) return@mapNotNull null
            val (ref, text) = line.split('@').also {
                check(it.size >= 2) { "error reading line: $line in ref: $ref" }
            }
            Line(ref, text)
        }
    }

    override suspend fun getSchema(ref: String): List<Schema> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("http://localhost:8080/schema.$ref")
            .build()

        val result = okHttpClient.newCall(request).execute()

        if (result.code == 404) throw Error("error fetching schema.$ref")

        result.body.string().lines().mapNotNull { line ->
            if (line.trim().isEmpty()) return@mapNotNull null
            val (ref, subRefs) = line.split('@').also {
                check(it.size >= 2) { "error reading line: $line in ref: $ref" }
            }
            if (subRefs.isDigitsOnly()) {
                SchemaInt(ref, subRefs.toInt())
            } else {
                SchemaString(ref, subRefs
                    .trim('[', ']')
                    .split(',')
                    .map { it -> it.trim(' ', '\'', '"')})
            }
        }
    }
}