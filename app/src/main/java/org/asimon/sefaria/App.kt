package org.asimon.sefaria

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.asimon.sefaria.data.local.SefariaDb
import org.asimon.sefaria.data.repo.LibraryRepository
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var scope: CoroutineScope
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var db: SefariaDb

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            library.refreshBuild() // learn the server's build so stale cache entries revalidate
            db.cache().trim(CACHE_BYTES) // KISS: trim at startup instead of a WorkManager job
        }
    }

    companion object {
        const val CACHE_BYTES = 200L * 1024 * 1024
    }
}
