package org.asimon.sefaria.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.asimon.sefaria.MainActivity
import org.asimon.sefaria.R
import org.asimon.sefaria.Startup
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.DownloadState
import java.util.concurrent.TimeUnit

/**
 * Keeps the process alive while books download, with a progress notification. The downloads
 * themselves run in [DownloadRepository]; this worker resumes its saved queue (after the system
 * ended the process), waits until the queue is done, and asks to run again when the network
 * stopped some of them (WorkManager waits for a connection).
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val downloads: DownloadRepository,
) : CoroutineWorker(ctx, params) {
    private val seen = HashSet<String>() // books this run has shown, for "n of m"

    override suspend fun doWork(): Result {
        Startup.settled() // the first screen's loading goes first
        try {
            setForeground(foregroundInfo())
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background: the
            // downloads still run, and the worker is stopped and run again if it takes too long
            Log.w(TAG, "no foreground: ${e.message}")
        }
        downloads.resumePending()
        coroutineScope {
            val progress = launch {
                downloads.downloads.sample(1000).collect { notify(it) }
            }
            downloads.awaitIdle()
            progress.cancel()
        }
        return if (downloads.hasPending()) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = notification(downloads.downloads.value)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun notify(states: Map<String, DownloadState>) {
        runCatching { NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification(states)) } // no permission: nothing shows
    }

    private fun notification(states: Map<String, DownloadState>): Notification {
        val ctx = applicationContext
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.notification_channel_downloads)).build()
        )
        val active = states.filterValues { it.active }.keys
        seen += active
        val done = seen.size - active.size
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.notification_downloading))
            .apply { if (seen.isNotEmpty()) setContentText(ctx.getString(R.string.notification_books_done, done, seen.size)) }
            .setProgress(seen.size, done, seen.isEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val NAME = "downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1

        /** Runs the worker unless it is already running or waiting to. */
        fun start(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
