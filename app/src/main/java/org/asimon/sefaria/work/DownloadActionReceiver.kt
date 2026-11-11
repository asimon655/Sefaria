package org.asimon.sefaria.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.asimon.sefaria.data.repo.DownloadRepository
import javax.inject.Inject

/**
 * The download notification's button: pauses every download, queued or running. Their partial
 * files stay, so each resumes from where it stopped when asked again (the library's rows show
 * them as paused).
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var downloads: DownloadRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PAUSE_ALL) downloads.cancelAll(downloads.downloads.value.keys)
    }

    companion object {
        const val PAUSE_ALL = "org.asimon.sefaria.PAUSE_DOWNLOADS"
    }
}
