package org.asimon.sefaria.data.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.Schema
import java.io.File
import javax.inject.Inject

class FileDownloadDataSource
@Inject constructor(
    @ApplicationContext appContext: Context)
    : DownloadDataSource {
    val folder = File(appContext.cacheDir, "downloaded")
    val books = HashSet<String>()
    init {
        if (!folder.exists()) folder.mkdir()
        folder.walk().filter { it.isFile }.forEach { file ->
            books.add(file.name)
        }
    }
    override suspend fun putText(ref: String, text: List<Line>) {
        books.add(ref)
        val file = File(folder, ref)
        text.joinTo(file.bufferedWriter(), "\n")
    }

    override suspend fun getText(ref: String): List<Line>? {
        if (!books.contains(ref.substringBefore('.'))) return null

    }

    override suspend fun delText(ref: String) {
        books.remove(ref)
        val file = File(folder, ref)
        file.delete()
    }

    override suspend fun putSchema(ref: String, schema: List<Schema>) {
        TODO("Not yet implemented")
    }

    override suspend fun getSchema(ref: String): List<Schema>? {
        TODO("Not yet implemented")
    }

    override suspend fun delSchema(ref: String) {
        TODO("Not yet implemented")
    }
}