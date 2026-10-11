package org.asimon.sefaria.feature.reader

import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.PaddingValues
import org.asimon.sefaria.ui.components.pick
import org.asimon.sefaria.feature.connections.connectionItems
import org.asimon.sefaria.feature.connections.ConnectionsViewModel
import org.asimon.sefaria.ReaderRoute
import org.asimon.sefaria.ui.components.label
import org.asimon.sefaria.ui.components.text
import org.asimon.sefaria.data.repo.AppError
import androidx.compose.ui.res.stringResource
import org.asimon.sefaria.R
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.asimon.sefaria.core.text.RichText
import org.asimon.sefaria.data.Layout
import org.asimon.sefaria.data.Prefs
import org.asimon.sefaria.data.ThemeMode
import org.asimon.sefaria.ui.components.BackButton
import org.asimon.sefaria.ui.components.ErrorMessage
import org.asimon.sefaria.ui.components.Loading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(onBack: () -> Unit, onOpen: (ReaderRoute) -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val title by remember {
        derivedStateOf {
            when (val item = s.items.getOrNull(list.firstVisibleItemIndex)) {
                is SegmentItem -> item.sectionTitle
                is HeaderItem -> item.title
                null -> s.bookTitle
            }
        }
    }

    // scroll to the target once, then let the view model page in both directions
    LaunchedEffect(s.initialIndex) {
        val idx = s.initialIndex ?: return@LaunchedEffect
        val key = s.items.getOrNull(idx)?.key
        list.scrollToItem(idx)
        // page only once the target is laid out; a prepend before that would shift the
        // pending index into the previous section
        withTimeoutOrNull(2000) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
        }
        vm.initialScrollDone()
        vm.onVisible(list.firstVisibleItemIndex, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: idx)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (first, last) -> vm.onVisible(first, last) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (s.offline) Icon(Icons.Outlined.CloudOff, contentDescription = stringResource(R.string.offline_copy), Modifier.padding(end = 8.dp))
                    IconButton(onClick = { showDisplay = true }) { Icon(Icons.Outlined.TextFields, contentDescription = stringResource(R.string.display_settings)) }
                },
            )
        },
    ) { padding ->
        when {
            s.error != null -> ErrorMessage(s.error!!.text(), { vm.open() }, Modifier.padding(padding))
            s.loading -> Loading(Modifier.padding(padding))
            // No item above the first section: the first visible item must be text, so that
            // LazyColumn keeps it in place when the previous section is prepended. Loading
            // or failing to load that section shows as an overlay instead.
            else -> Box(Modifier.fillMaxWidth().padding(padding)) {
              LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
                items(s.items, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is HeaderItem -> SectionHeader(item.title)
                        is SegmentItem -> SegmentRow(item, prefs, selected = item.key == selectedKey) { selectedKey = item.key }
                    }
                }
                item(key = "bottom") {
                    EdgeRow(loading = s.loadingNext, error = s.nextError, atEdge = s.atEnd, edgeText = stringResource(R.string.end_of_book, s.bookTitle), onRetry = vm::loadNext)
                }
              }
              if (s.loadingPrev) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
              s.prevError?.let { e ->
                  TextButton(onClick = vm::loadPrev, modifier = Modifier.align(Alignment.TopCenter)) {
                      Text(e.text() + " · " + stringResource(R.string.retry))
                  }
              }
            }
        }
    }

    if (showDisplay) DisplaySheet(prefs, vm, onDismiss = { showDisplay = false })
    val selected = s.items.firstOrNull { it.key == selectedKey } as? SegmentItem
    if (selected != null) SegmentSheet(selected, vm, prefs, onOpen = { selectedKey = null; onOpen(it) }, onDismiss = { selectedKey = null })
}

@Composable
private fun EdgeRow(loading: Boolean, error: AppError?, atEdge: Boolean, edgeText: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.width(24.dp))
            error != null -> TextButton(onClick = onRetry) { Text(error.text() + " · " + stringResource(R.string.retry)) }
            atEdge && edgeText.isNotEmpty() -> Text(edgeText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}

private fun heStyle(scale: Float) = TextStyle(
    fontFamily = FontFamily.Serif, fontSize = (21 * scale).sp, lineHeight = 1.55.em, textDirection = TextDirection.Rtl,
)

private fun enStyle(scale: Float) = TextStyle(
    fontFamily = FontFamily.Serif, fontSize = (17 * scale).sp, lineHeight = 1.5.em, textDirection = TextDirection.Ltr,
)

@Composable
private fun SegmentRow(item: SegmentItem, prefs: Prefs, selected: Boolean, onClick: () -> Unit) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        item.highlighted -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surface
    }
    val he = item.he
    val en = item.en
    val rtlRow = when (prefs.layout) {
        Layout.Hebrew, Layout.Stacked -> he != null
        Layout.English, Layout.SideBySide -> en == null
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtlRow) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(bg)
                .clickable(onClick = onClick)
                .semantics { contentDescription = item.ref.human() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // the number, with a notes marker under it, so text columns stay aligned
            Column(Modifier.widthIn(min = 28.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (item.links > 0) Text(
                    item.links.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.semantics { contentDescription = "" },
                )
                if ((he?.notes.orEmpty() + en?.notes.orEmpty()).isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = stringResource(R.string.has_notes), Modifier.padding(top = 4.dp).width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(6.dp))
            val scale = prefs.fontScale
            when (prefs.layout) {
                Layout.Hebrew -> SegText(he ?: en, if (he != null) heStyle(scale) else enStyle(scale), Modifier.weight(1f))
                Layout.English -> SegText(en ?: he, if (en != null) enStyle(scale) else heStyle(scale), Modifier.weight(1f))
                Layout.Stacked -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SegText(he, heStyle(scale))
                    SegText(en, enStyle(scale))
                }
                Layout.SideBySide -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (en != null) SegText(en, enStyle(scale), Modifier.weight(1f))
                    if (he != null) SegText(he, heStyle(scale), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SegText(text: RichText?, style: TextStyle, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(text.text, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentSheet(
    item: SegmentItem,
    vm: ReaderViewModel,
    prefs: Prefs,
    onOpen: (ReaderRoute) -> Unit,
    onDismiss: () -> Unit,
    cvm: ConnectionsViewModel = hiltViewModel(),
) {
    val bookmarked by remember(item.key) { vm.bookmarked(item.ref) }.collectAsState(initial = false)
    LaunchedEffect(item.key) { cvm.load(item.key, item.ref.book.title) }
    val conn by cvm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val citation = buildString {
        item.he?.let { append(it.plain).append('\n') }
        item.en?.let { append(it.plain).append('\n') }
        append("— ").append(item.ref.human())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pick(item.ref.human(), item.ref.hebrew()), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { vm.toggleBookmark(item, bookmarked) }) {
                        Icon(
                            if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(if (bookmarked) R.string.remove_bookmark else R.string.bookmark),
                        )
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(citation))
                        onDismiss()
                    }) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.copy_with_citation)) }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, citation)
                        context.startActivity(Intent.createChooser(send, null))
                        onDismiss()
                    }) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share)) }
                }
            }
            val notes = item.he?.notes.orEmpty() + item.en?.notes.orEmpty()
            if (notes.isNotEmpty()) {
                item(key = "notes") {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        for ((i, n) in notes.withIndex()) Text(
                            "${i + 1}. $n",
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item(key = "div") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            connectionItems(conn, prefs, onToggle = cvm::toggle, onRetry = { cvm.load(item.key, item.ref.book.title) }, onOpen = onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySheet(prefs: Prefs, vm: ReaderViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.layout), style = MaterialTheme.typography.titleSmall)
            val layouts = listOf(Layout.Hebrew to R.string.layout_hebrew, Layout.English to R.string.layout_english, Layout.Stacked to R.string.layout_both, Layout.SideBySide to R.string.layout_side)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = prefs.layout == l,
                        onClick = { vm.setLayout(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(stringResource(label), maxLines = 1) }
                }
            }
            Text(stringResource(R.string.font_size), style = MaterialTheme.typography.titleSmall)
            var scale by remember { mutableStateOf(prefs.fontScale) }
            Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { vm.setFontScale(scale) }, valueRange = 0.7f..2f)
            SwitchRow(stringResource(R.string.niqqud), prefs.niqqud, vm::setNiqqud)
            SwitchRow(stringResource(R.string.cantillation), prefs.cantillation, vm::setCantillation)
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
            val themes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = prefs.theme == t,
                        onClick = { vm.setTheme(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) { Text(t.label(), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
