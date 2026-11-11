package org.asimon.sefaria.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.asimon.sefaria.data.local.CacheDb
import org.asimon.sefaria.data.local.LibraryFiles
import org.asimon.sefaria.data.local.UserDb
import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping while the phone is idle: trims the response cache to [CACHE_BYTES] (least
 * recently used first), tidies the downloaded library (LibraryFiles.tidy) and keeps the history
 * to its latest [UserDb.HISTORY_KEEP] visits.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val cache: CacheDb,
    private val library: LibraryFiles,
    private val user: UserDb,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // each on its own: the cache database can be gone (the system empties the cache folder),
        // and that mustn't keep the library or the history from being tidied
        val steps = listOf<suspend () -> Unit>(
            { cache.cache().trim(CACHE_BYTES) },
            { library.tidy() },
            { user.user().pruneHistory(UserDb.HISTORY_KEEP) },
        )
        var failed = false
        for (step in steps) {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Maintenance", "a step failed", e)
                failed = true
            }
        }
        return if (failed) Result.failure() else Result.success()
    }

    companion object {
        const val CACHE_BYTES = 200L * 1024 * 1024
        private const val NAME = "maintenance"

        /** Schedules the daily run, keeping the schedule if there is one. */
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
