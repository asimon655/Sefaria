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
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping while the phone is idle: trims the response cache to [CACHE_BYTES] (least
 * recently used first) and tidies the downloaded library (LibraryFiles.tidy).
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val cache: CacheDb,
    private val library: LibraryFiles,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        cache.cache().trim(CACHE_BYTES)
        library.tidy()
        return Result.success()
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
