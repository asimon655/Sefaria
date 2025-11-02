package org.asimon.sefaria.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.serialization.serializer
import okhttp3.Interceptor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.measureTime

internal suspend inline fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        val callback = ContinuationCallback(this, continuation)
        enqueue(callback)
        continuation.invokeOnCancellation(callback)
    }
}

internal class ContinuationCallback(
    private val call: Call,
    private val continuation: CancellableContinuation<Response>
) : Callback, CompletionHandler {

    override fun onResponse(call: Call, response: Response) {
        continuation.resume(response)
    }

    override fun onFailure(call: Call, e: IOException) {
        if (!call.isCanceled()) {
            continuation.resumeWithException(e)
        }
    }

    override fun invoke(cause: Throwable?) {
        try {
            call.cancel()
        } catch (_: Throwable) {}
    }
}

fun areAnimationsDisabled(context: Context): Boolean {
    val animationDurationScale = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE
        )
    } catch (e: Settings.SettingNotFoundException) {
        // If the setting is not found, it typically means it's at its default (enabled) state.
        // We can treat this as animations being enabled.
        1f // A non-zero value indicating animations are not off
    }
    // Animations are considered "off" or significantly reduced if the scale is 0f.
    return animationDurationScale == 0f
}

sealed interface SuspendedDataState<out T> {
    data object Loading: SuspendedDataState<Nothing>
    data class Success<T>(
        val data: T
    ): SuspendedDataState<T>
    data class Error(
        val message: String
    ): SuspendedDataState<Nothing>
}

fun median(l: List<Int>): Int {
    if (l.isEmpty()) {
        return 0
    } else if (l.size <= 5) {
        return l.sorted()[l.size/2]
    } else {
        val l1 = mutableListOf<Int>()
        for (i in 0..l.size step 5) {
            l1.add(median(l.subList(i, min(i+5,l.size))))
        }
        return median(l1)
    }
}

fun mostCommonKey(keys: List<String>): String {
    assert(keys.isNotEmpty()) { "keys list must have at least one key" }
    var key = keys.first()
    while (!keys.all { it.startsWith(key) }) key = key.substringBeforeLast('.')
    return key
}


// custom hash map
// the hash map doest store the original keys rather its stores the offset to the entry in a file
// that way the data structure could be stored easly in memory
// maybe i should use Int over Long for performance
data class KvEntry(
    val hash: Int,
    var offset: Long,
    var next: KvEntry?,
)

class FileCacheKv(val size: Int, file: File) {
    private val map = Array<KvEntry?>(size/100) { null }
    private var clock = 0
    private val cacheFileTextAccess = RandomAccessFile(file, "rw")

    init {
        cacheFileTextAccess.seek(0)
        while (cacheFileTextAccess.filePointer < cacheFileTextAccess.length()) {
            val offset = cacheFileTextAccess.filePointer
            val line = cacheFileTextAccess.readUTF()
            clock = max(clock, cacheFileTextAccess.readInt())
            val key = mostCommonKey(line.split('\n').map { it.substringBefore('@') })
            val hash = key.hashCode()
            val index = abs(hash % map.size)
            val next = map[index]
            map[index] = KvEntry(hash, offset, next)
        }
    }

    fun put(key: String, value: String) {
        val hash = key.hashCode()
        val index = abs(hash % map.size)
        val next = map[index]
        val offset = cacheFileTextAccess.length()
        cacheFileTextAccess.seek(offset)
        cacheFileTextAccess.writeUTF(value)
        cacheFileTextAccess.writeInt(clock++)
        map[index] = KvEntry(hash, offset, next)

        if (cacheFileTextAccess.length() > size) {
            val duration = measureTime {
                shrink()
            }
            println("shrink took: ${duration.inWholeSeconds}")
        }
    }

    fun get(key: String) : String? {
        val hash = key.hashCode()
        val index = abs(hash % map.size)
        var entry = map[index]
        while (entry != null) {
            if (entry.hash == hash) {
                cacheFileTextAccess.seek(entry.offset)
                val line = cacheFileTextAccess.readUTF()
                if (line.startsWith(key)) {
                    cacheFileTextAccess.writeInt(clock++)
                    return line
                }
            }
            entry = entry.next
        }
        return null
    }

    private fun getEntryByOffset(key: String, offset: Long) : KvEntry? {
        val index = abs(key.hashCode() % map.size)
        var entry = map[index]
        while (entry != null) {
            if (entry.offset == offset) {
                return entry
            }
            entry = entry.next
        }
        return null
    }

    private fun removeEntryByOffset(key: String, offset: Long) {
        val index = abs(key.hashCode() % map.size)
        var entry = map[index]
        var prevEntry: KvEntry? = null
        while (entry != null) {
            if (entry.offset == offset) {
                if (prevEntry != null) {
                    prevEntry.next = entry.next
                } else {
                    map[index] = null
                }
            }
            prevEntry = entry
            entry = entry.next
        }
    }

    fun shrink() {
        val accessList = mutableListOf<Int>()
        cacheFileTextAccess.seek(0)
        while (cacheFileTextAccess.filePointer < cacheFileTextAccess.length()) {
            // val line = cacheFileTextAccess.readUTF()
            val strLength = cacheFileTextAccess.readUnsignedShort()
            cacheFileTextAccess.skipBytes(strLength)
            val lastAccessed = cacheFileTextAccess.readInt()
            accessList.add(lastAccessed)
        }
        val med = median(accessList)

        var cursorRead = 0L
        var cursorWrite = 0L
        while (cursorRead < cacheFileTextAccess.length()) {
            val entryOffset = cursorRead
            cacheFileTextAccess.seek(cursorRead)
            val line = cacheFileTextAccess.readUTF()
            val lastAccessed = cacheFileTextAccess.readInt()
            cursorRead = cacheFileTextAccess.filePointer
            val key = mostCommonKey(line.split('\n').map { it.substringBefore('@') })
            if (lastAccessed > med) {
                val entry = getEntryByOffset(key, entryOffset)
                checkNotNull(entry) { "error!! didnt found the entry in the map for the key: " + key }
                entry.offset = cursorWrite
                cacheFileTextAccess.seek(cursorWrite)
                cacheFileTextAccess.writeUTF(line)
                cacheFileTextAccess.writeInt(lastAccessed)
                cursorWrite = cacheFileTextAccess.filePointer
            } else {
                removeEntryByOffset(key, entryOffset)
            }
        }
        cacheFileTextAccess.setLength(cursorWrite)
    }
}

@OptIn(ExperimentalSerializationApi::class)
class FileCache<T>(val file: File, private val serializer: KSerializer<T>) {
    var content: T? = null
    init {
        if (file.length() > 0)
            content = Json.decodeFromStream(serializer, file.inputStream())
    }
    fun put(value: T) {
        content = value
        Json.encodeToStream(serializer, value, file.outputStream())
    }
    fun get(): T? {
        return content
    }
}

inline fun <reified T> FileCache(file: File) = FileCache(file, serializer<T>())

public class OkHttpExceptionInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: Throwable) {
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }
}