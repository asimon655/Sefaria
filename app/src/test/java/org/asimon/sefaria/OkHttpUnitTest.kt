package org.asimon.sefaria

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import org.asimon.sefaria.data.network.OkHttpNetworkDataSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.asimon.sefaria.data.network.NetworkDataSource
import org.junit.Before
import org.junit.Test
import javax.inject.Inject

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */

class OkHttpUnitTest {
    lateinit var dataSource: NetworkDataSource

    @Before
    fun init() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.isLoggable(any<String>(), any<Int>()) } returns false


        dataSource = OkHttpNetworkDataSource(OkHttpClient(), Dispatchers.IO)
    }

    @Test
    fun getTableOfContent() = runTest {
        val tableOfContent = dataSource.getTableOfContent()
        println("size: ${tableOfContent.size}")
    }
}