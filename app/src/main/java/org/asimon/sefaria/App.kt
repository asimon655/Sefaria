package org.asimon.sefaria

import android.app.ActivityManager
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.LibraryRepository
import org.asimon.sefaria.work.DownloadWorker
import org.asimon.sefaria.work.MaintenanceWorker
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Starts nothing on the main thread: the singletons are built off it (Lazy), and background
 * work (WorkManager) is scheduled once the first screen is up ([Startup]).
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var scope: CoroutineScope
    @Inject lateinit var library: Lazy<LibraryRepository>
    @Inject lateinit var downloads: Lazy<DownloadRepository>
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // the build id now: it decides whether the first screen's cached responses are current
        scope.launch { library.get().refreshBuild() }
        scope.launch {
            Startup.ready.await()
            MaintenanceWorker.schedule(this@App)
            // the queue the last run left: WorkManager restarts its worker, but this also covers a
            // run that ended before the worker was recorded (a running worker is kept, not doubled)
            if (downloads.get().hasSavedQueue()) DownloadWorker.start(this@App)
        }
    }
}

/** When the app is up: its first frame drawn and the main thread idle. */
object Startup {
    val ready = CompletableDeferred<Unit>()
    @Volatile private var readyAt = 0L

    /** Called by the activity after its first frame. */
    fun firstFrameDrawn() {
        if (ready.isCompleted) return
        Looper.myQueue().addIdleHandler {
            readyAt = SystemClock.elapsedRealtime()
            ready.complete(Unit)
            false
        }
    }

    /**
     * For background work that can wait: in a process started for a screen, returns [SETTLE_MS]
     * after it is up, so the screen's own loading goes first; in one started in the background
     * (WorkManager after the process ended), at once.
     */
    suspend fun settled() {
        val me = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        if (me.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && !ready.isCompleted) return
        ready.await()
        delay((readyAt + SETTLE_MS - SystemClock.elapsedRealtime()).milliseconds)
    }

    private const val SETTLE_MS = 2000L
}
