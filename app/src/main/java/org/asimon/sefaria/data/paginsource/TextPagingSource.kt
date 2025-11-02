package org.asimon.sefaria.data.paginsource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.flow.first
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.SchemaInt
import org.asimon.sefaria.data.SchemaString
import org.asimon.sefaria.data.repositories.DataRepository
import kotlin.collections.find
import kotlin.collections.first

class TextPagingSource(
    val backend: DataRepository,
    val ref: String
) : PagingSource<String, Line>() {
    override suspend fun load(
        params: LoadParams<String>
    ): LoadResult<String, Line> {
        try {
            val schemas = backend.getSchema(ref).first()
            val schema = schemas.find { schema -> schema.ref == ref }
            checkNotNull(schema) { "couldn't find the schema for ref: $ref in schema: $schema" }

            when (schema) {
                is SchemaInt -> {
                    val key = params.key?.toInt() ?: 1
                    val nextKey = if ((key + 1) <= schema.subRefs) key + 1 else null
                    val response = backend.getText("$ref.$key").first()
                    return LoadResult.Page(
                        data = response,
                        prevKey = null,
                        nextKey = nextKey?.toString()
                    )
                }

                is SchemaString -> {
                    val key = params.key ?: schema.subRefs.first()
                    val nextKeyIndex = schema.subRefs.indexOf(key) + 1
                    val nextKey =
                        if (nextKeyIndex < schema.subRefs.size) schema.subRefs[nextKeyIndex] else null
                    val response = backend.getText("$ref.$key").first()
                    return LoadResult.Page(
                        data = response,
                        prevKey = null,
                        nextKey = nextKey
                    )
                }
            }
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<String, Line>): String? {
        // Try to find the page key of the closest page to anchorPosition from
        // either the prevKey or the nextKey; you need to handle nullability
        // here.
        //  * prevKey == null -> anchorPage is the first page.
        //  * nextKey == null -> anchorPage is the last page.
        //  * both prevKey and nextKey are null -> anchorPage is the
        //    initial page, so return null.
        return null
    }
}