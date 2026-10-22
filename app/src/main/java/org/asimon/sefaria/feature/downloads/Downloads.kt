package org.asimon.sefaria.feature.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import org.asimon.sefaria.data.Settings
import android.text.format.Formatter
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.asimon.sefaria.R
import org.asimon.sefaria.data.local.InstalledBook
import org.asimon.sefaria.data.local.ListedFile
import org.asimon.sefaria.data.repo.DownloadRepository
import org.asimon.sefaria.data.repo.DownloadState
import org.asimon.sefaria.data.repo.LibraryListing
import org.asimon.sefaria.data.repo.SharedFiles
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val repo: DownloadRepository,
    private val listing: LibraryListing,
    shared: SharedFiles,
    private val settings: Settings,
) : ViewModel() {
    val installed = repo.installed
    val notificationsAsked = settings.prefs.map { it.notificationsAsked }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val downloads = repo.downloads
    /**
     * Whether anything is queued or downloading, checked off the main thread and at most every
     * [THROTTLE_MS] (the latest state wins): it changes twice per batch, not on every update.
     */
    val downloading: StateFlow<Boolean> = downloads
        .throttleLatest(THROTTLE_MS)
        .map { m -> m.values.any { it.active } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val bytes = repo.bytes
    /** The server's books by title, as last listed; empty if never listed. */
    val available = listing.available
    /** What a download adds beyond the book: the connections file and forms table, until downloaded. */
    val extra = listing.extra

    init {
        shared.syncLibrary()
    }

    fun download(book: String) = viewModelScope.launch { if (listing.isListed(book)) repo.download(book) }
    fun downloadAll(books: Collection<String>) = viewModelScope.launch { repo.downloadAll(books) }
    fun cancelAll(books: Collection<String>) = repo.cancelAll(books)
    fun cancel(book: String) = repo.cancel(book)
    fun delete(book: String) = viewModelScope.launch { repo.delete(book) }
    fun setNotificationsAsked() = viewModelScope.launch { settings.setNotificationsAsked() }
    /** Pass the [available] entry read in composition, so the UI updates when the listing arrives. */
    fun updateAvailable(p: InstalledBook, available: ListedFile?) = listing.updateAvailable(p, available)

    /**
     * [books]' [GroupSummary], off the main thread and at most every [THROTTLE_MS]: the
     * three maps change several times per book while a whole category downloads, and each
     * summary walks the group (the whole library for the top bar).
     */
    fun groupSummary(books: List<String>): Flow<GroupSummary?> =
        combine(installed, available, downloads, ::Triple)
            .throttleLatest(THROTTLE_MS)
            .map { (i, a, d) -> summarize(books, i, a, d) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** [books]' summary now, for the first frame (the flow's first value comes a moment later). */
    fun groupSummaryNow(books: List<String>): GroupSummary? =
        summarize(books, installed.value, available.value, downloads.value)

    /** Deletes the downloaded books of [books]. */
    fun deleteDownloaded(books: List<String>) = viewModelScope.launch {
        val have = installed.value
        repo.deleteAll(books.filter { it in have })
    }

    private companion object {
        const val THROTTLE_MS = 250L
    }
}

/**
 * A group of books (a category, or the whole library) as its download control shows it: books
 * the server offers or that are downloaded, how many are downloaded and current ([done]),
 * queued or downloading ([active]), still to download ([missing], [missingBytes]), and
 * downloaded in any version ([have], [haveBytes]).
 */
data class GroupSummary(
    val offered: Int,
    val done: Int,
    val active: Int,
    val missing: Int,
    val missingBytes: Long,
    val have: Int,
    val haveBytes: Long,
)

/** Null until the server's listing is known (installed books alone would make a group look complete), or if the group offers nothing. */
private fun summarize(
    books: List<String>,
    installed: Map<String, InstalledBook>,
    available: Map<String, ListedFile>,
    downloads: Map<String, DownloadState>,
): GroupSummary? {
    if (available.isEmpty()) return null
    var offered = 0; var active = 0; var missing = 0; var have = 0
    var missingBytes = 0L; var haveBytes = 0L
    for (b in books) {
        val listed = available[b]
        val local = installed[b]
        if (listed == null && local == null) continue
        offered++
        if (local != null) { have++; haveBytes += local.bytes }
        if (listed != null && local?.file != listed.file) { missing++; missingBytes += listed.downloadBytes }
        if (b in downloads) active++
    }
    if (offered == 0) return null
    return GroupSummary(offered, offered - missing, active, missing, missingBytes, have, haveBytes)
}

/** The first value at once, then the latest at most every [ms]. */
private fun <T> Flow<T>.throttleLatest(ms: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(ms)
}

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * One entry of a map flow, as state that changes only when that entry does: a book's control
 * then redraws for its own book, not each time another book's download moves.
 */
@Composable
private fun <V> StateFlow<Map<String, V>>.collectEntry(key: String): State<V?> =
    remember(this, key) { map { it[key] }.distinctUntilChanged() }.collectAsStateWithLifecycle(value[key])

/**
 * The download control on a book page; shows nothing if the book can't be downloaded. The
 * size is the book's own (as the library list and the downloaded size count it); the files
 * the first download also brings (connections, search forms) show as a line of their own.
 */
@Composable
fun BookDownload(book: String, he: String, vm: DownloadsViewModel) {
    val pack = vm.installed.collectEntry(book).value
    val download = vm.downloads.collectEntry(book).value
    val listed = vm.available.collectEntry(book).value
    val extra by vm.extra.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (val d = download) {
        is DownloadState.Running -> Progress(d, onCancel = { vm.cancel(book) }, modifier = pad)
        DownloadState.Queued -> Progress(DownloadState.Running(0, 0), onCancel = { vm.cancel(book) }, modifier = pad)
        DownloadState.Verifying -> Verifying(onCancel = { vm.cancel(book) }, modifier = pad)
        is DownloadState.Failed -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.download_failed, d.error.text()), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.download(book) }) { Text(stringResource(R.string.retry)) }
        }
        null -> if (pack != null) Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.downloaded_size, size(pack.bytes)), Modifier.weight(1f).padding(horizontal = 8.dp))
            if (vm.updateAvailable(pack, listed)) TextButton(onClick = { vm.download(book) }) { Text(stringResource(R.string.update)) }
            IconButton(onClick = { deleting = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_download))
            }
        } else listed?.let { p ->
            Column(pad) {
                OutlinedButton(onClick = { vm.download(book) }) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text(stringResource(R.string.download_size, size(p.downloadBytes)), Modifier.padding(start = 8.dp))
                }
                if (extra > 0) ExtraNote(extra)
            }
        }
    }
    if (deleting) ConfirmDelete(
        title = stringResource(R.string.delete_download_title),
        body = stringResource(R.string.delete_download_body, pick(book, he)),
        onConfirm = { deleting = false; vm.delete(book) },
        onDismiss = { deleting = false },
    )
}

/** What the first download also brings, once. */
@Composable
private fun ExtraNote(extra: Long) = Text(
    stringResource(R.string.download_extra, size(extra)),
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Asks before deleting downloads: they take time (and data) to download again. */
@Composable
fun ConfirmDelete(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Progress(d: DownloadState.Running, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.downloading, size(d.done), size(d.total)), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel)) }
    }
}

/** A downloaded book waiting for, or in, the verify queue. */
@Composable
private fun Verifying(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.verifying), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel)) }
    }
}

/**
 * A book's download control in a list (the library): download, queued or in progress (tap to
 * cancel), downloaded, update, or retry after a failure. Nothing if the server can't send it.
 */
@Composable
fun BookRowDownload(book: String, he: String, vm: DownloadsViewModel) {
    val have = vm.installed.collectEntry(book).value
    val download = vm.downloads.collectEntry(book).value
    val listed = vm.available.collectEntry(book).value
    var deleting by remember { mutableStateOf(false) }
    when (val d = download) {
        DownloadState.Queued, DownloadState.Verifying -> IconButton(onClick = { vm.cancel(book) }) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        is DownloadState.Running -> IconButton(onClick = { vm.cancel(book) }) {
            CircularProgressIndicator(progress = { if (d.total > 0) d.done.toFloat() / d.total else 0f }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        is DownloadState.Failed -> IconButton(onClick = { vm.download(book) }) {
            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retry), tint = MaterialTheme.colorScheme.error)
        }
        null -> when {
            have != null && vm.updateAvailable(have, listed) -> IconButton(onClick = { vm.download(book) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.update))
            }
            // downloaded: tapping offers to delete it
            have != null -> IconButton(onClick = { deleting = true }) {
                Icon(Icons.Outlined.DownloadDone, contentDescription = stringResource(R.string.remove_download), tint = MaterialTheme.colorScheme.primary)
            }
            listed != null -> IconButton(onClick = { vm.download(book) }) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download))
            }
        }
    }
    if (deleting && have != null) ConfirmDelete(
        title = stringResource(R.string.delete_download_title),
        body = stringResource(R.string.delete_download_body, pick(book, he)) + " (" + size(have.bytes) + ")",
        onConfirm = { deleting = false; vm.delete(book) },
        onDismiss = { deleting = false },
    )
}

/**
 * The download control for a group of books (a category, or the whole library). Tapping asks
 * first, with the number of books and their size, and offers to delete the group's downloaded
 * books too (asking again). While some download, it shows how many are done; tapping cancels
 * the rest.
 */
@Composable
fun GroupDownload(name: String, books: List<String>, vm: DownloadsViewModel, asButton: Boolean = false) {
    // counted in the view model: this redraws only when a number it shows changes
    val s = remember(vm, books) { vm.groupSummary(books) }
        .collectAsStateWithLifecycle(remember(vm, books) { vm.groupSummaryNow(books) }).value
    val extra by vm.extra.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // nothing until the server's listing is known (offline, deleting stays possible from each
    // book's page and the settings)
    if (s == null) return
    when {
        // the actions take the whole group: the repository skips what doesn't apply
        s.active > 0 -> IconButton(onClick = { vm.cancelAll(books) }) {
            CircularProgressIndicator(progress = { s.done.toFloat() / s.offered }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        s.missing == 0 -> IconButton(onClick = { deleting = true }) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = stringResource(R.string.remove_download), tint = MaterialTheme.colorScheme.primary)
        }
        asButton -> TextButton(onClick = { asking = true }) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Text(stringResource(R.string.download_all), Modifier.padding(start = 6.dp))
        }
        else -> IconButton(onClick = { asking = true }) {
            Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.download_all_of, name))
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.download_all_of, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.download_confirm, s.missing, size(s.missingBytes)))
                    if (extra > 0) ExtraNote(extra)
                    if (s.have > 0) TextButton(onClick = { asking = false; deleting = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.delete_downloaded_n, s.have), Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            // downloadAll queues only the books that are listed and not downloaded and current
            confirmButton = { TextButton(onClick = { asking = false; vm.downloadAll(books) }) { Text(stringResource(R.string.download)) } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) ConfirmDelete(
        title = stringResource(R.string.delete_group_title),
        body = stringResource(R.string.delete_group_body, s.have, size(s.haveBytes), name),
        onConfirm = { deleting = false; vm.deleteDownloaded(books) },
        onDismiss = { deleting = false },
    )
}

/**
 * Asks once for the notification permission (Android 13+) when a download first starts, so the
 * download notification can show its progress. Denied, downloads still run.
 */
@Composable
fun AskNotificationsOnDownload(vm: DownloadsViewModel) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val asked by vm.notificationsAsked.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(downloading, asked) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (downloading && !asked && !granted) {
            vm.setNotificationsAsked()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
