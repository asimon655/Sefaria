package org.asimon.sefaria.feature.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.asimon.sefaria.R
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.data.local.InstalledPack
import org.asimon.sefaria.data.remote.PackDto
import org.asimon.sefaria.data.repo.DownloadState
import org.asimon.sefaria.data.repo.Load
import org.asimon.sefaria.data.repo.PackRepository
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.ui.components.text
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val packs: PackRepository) : ViewModel() {
    val installed = packs.installed
    val downloads = packs.downloads
    private val _available = MutableStateFlow<Map<String, PackDto>>(emptyMap())
    /** The server's packs by book; empty while unknown (e.g. offline). */
    val available = _available.asStateFlow()

    init {
        viewModelScope.launch {
            packs.scan()
            (packs.available() as? Load.Ok)?.let { _available.value = it.value }
        }
    }

    fun download(book: String) { _available.value[book]?.let(packs::download) }
    fun cancel(book: String) = packs.cancel(book)
    fun delete(book: String) = viewModelScope.launch { packs.delete(book) }
    fun updateAvailable(p: InstalledPack) = packs.updateAvailable(p, _available.value[p.book])
}

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The download control on a book page; shows nothing if the book can't be downloaded. */
@Composable
fun BookDownload(book: String, vm: DownloadsViewModel) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val pack = installed[book]
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (val d = downloads[book]) {
        is DownloadState.Running -> Progress(d, onCancel = { vm.cancel(book) }, modifier = pad)
        is DownloadState.Failed -> Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.download_failed, d.error.text()), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.download(book) }) { Text(stringResource(R.string.retry)) }
        }
        null -> if (pack != null) Row(pad, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.downloaded_size, size(pack.bytes)), Modifier.weight(1f).padding(horizontal = 8.dp))
            if (vm.updateAvailable(pack)) TextButton(onClick = { vm.download(book) }) { Text(stringResource(R.string.update)) }
            IconButton(onClick = { vm.delete(book) }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_download))
            }
        } else available[book]?.let { p ->
            OutlinedButton(onClick = { vm.download(book) }, modifier = pad) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Text(stringResource(R.string.download_size, size(p.bytes)), Modifier.padding(start = 8.dp))
            }
        }
    }
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

/** The Downloads tab: downloaded books, downloads in progress, and the storage they use. */
@Composable
fun DownloadsList(onOpen: (ReaderRoute) -> Unit, vm: DownloadsViewModel) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val available by vm.available.collectAsStateWithLifecycle()
    val pending = downloads.keys.filter { it !in installed }.sorted()
    if (installed.isEmpty() && pending.isEmpty()) {
        Text(
            stringResource(R.string.empty_downloads),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn {
        item(key = "total") {
            Text(
                stringResource(R.string.storage_used, size(installed.values.sumOf { it.bytes })),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(pending, key = { "d:$it" }) { book ->
            val d = downloads[book]
            ListItem(
                headlineContent = { Text(available[book]?.let { pick(it.book, it.he) } ?: book) },
                supportingContent = {
                    when (d) {
                        is DownloadState.Running -> Progress(d, onCancel = { vm.cancel(book) })
                        is DownloadState.Failed -> Text(stringResource(R.string.download_failed, d.error.text()), color = MaterialTheme.colorScheme.error)
                        null -> Unit
                    }
                },
                trailingContent = {
                    if (d is DownloadState.Failed) IconButton(onClick = { vm.download(book) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.retry))
                    }
                },
            )
            HorizontalDivider()
        }
        items(installed.values.sortedBy { it.book }, key = { "i:" + it.book }) { p ->
            val d = downloads[p.book]
            ListItem(
                headlineContent = { Text(pick(p.book, p.he)) },
                supportingContent = {
                    when {
                        d is DownloadState.Running -> Progress(d, onCancel = { vm.cancel(p.book) })
                        d is DownloadState.Failed -> Text(stringResource(R.string.download_failed, d.error.text()), color = MaterialTheme.colorScheme.error)
                        vm.updateAvailable(p) -> Text(size(p.bytes) + " · " + stringResource(R.string.update_available), color = MaterialTheme.colorScheme.primary)
                        else -> Text(size(p.bytes))
                    }
                },
                trailingContent = {
                    Row {
                        if (d == null && vm.updateAvailable(p)) IconButton(onClick = { vm.download(p.book) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.update))
                        }
                        IconButton(onClick = { vm.delete(p.book) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_download))
                        }
                    }
                },
                modifier = Modifier.clickable { onOpen(ReaderRoute(p.book, p.book, highlight = false)) },
            )
            HorizontalDivider()
        }
    }
}
