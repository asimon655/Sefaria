package org.asimon.sefaria.feature.reader

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
fun ReaderScreen(onBack: () -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val title by remember {
        derivedStateOf {
            // list index 0 is the "top" edge row, so item i is at list index i + 1
            val first = (list.firstVisibleItemIndex - 1).coerceAtLeast(0)
            when (val item = s.items.getOrNull(first)) {
                is SegmentItem -> item.sectionTitle
                is HeaderItem -> item.title
                null -> s.bookTitle
            }
        }
    }

    // scroll to the target once, then let the view model page in both directions
    LaunchedEffect(s.initialIndex) {
        val idx = s.initialIndex ?: return@LaunchedEffect
        list.scrollToItem(idx + 1)
        vm.initialScrollDone()
        vm.onVisible(list.firstVisibleItemIndex - 1, (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: idx) - 1)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (first, last) -> vm.onVisible(first - 1, last - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (s.offline) Icon(Icons.Outlined.CloudOff, contentDescription = "Offline copy", Modifier.padding(end = 8.dp))
                    IconButton(onClick = { showDisplay = true }) { Icon(Icons.Outlined.TextFields, contentDescription = "Display settings") }
                },
            )
        },
    ) { padding ->
        when {
            s.error != null -> ErrorMessage(s.error!!, { vm.open() }, Modifier.padding(padding))
            s.loading -> Loading(Modifier.padding(padding))
            else -> LazyColumn(state = list, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
                item(key = "top") {
                    EdgeRow(loading = s.loadingPrev, error = s.prevError, atEdge = s.atStart, edgeText = "", onRetry = vm::loadPrev)
                }
                items(s.items, key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is HeaderItem -> SectionHeader(item.title)
                        is SegmentItem -> SegmentRow(item, prefs, selected = item.key == selectedKey) { selectedKey = item.key }
                    }
                }
                item(key = "bottom") {
                    EdgeRow(loading = s.loadingNext, error = s.nextError, atEdge = s.atEnd, edgeText = "End of ${s.bookTitle}", onRetry = vm::loadNext)
                }
            }
        }
    }

    if (showDisplay) DisplaySheet(prefs, vm, onDismiss = { showDisplay = false })
    val selected = s.items.firstOrNull { it.key == selectedKey } as? SegmentItem
    if (selected != null) SegmentSheet(selected, vm, onDismiss = { selectedKey = null })
}

@Composable
private fun EdgeRow(loading: Boolean, error: String?, atEdge: Boolean, edgeText: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.width(24.dp))
            error != null -> TextButton(onClick = onRetry) { Text("$error · Retry") }
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
                if ((he?.notes.orEmpty() + en?.notes.orEmpty()).isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = "Has notes", Modifier.padding(top = 4.dp).width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun SegmentSheet(item: SegmentItem, vm: ReaderViewModel, onDismiss: () -> Unit) {
    val bookmarked by remember(item.key) { vm.bookmarked(item.ref) }.collectAsState(initial = false)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val citation = buildString {
        item.he?.let { append(it.plain).append('\n') }
        item.en?.let { append(it.plain).append('\n') }
        append("— ").append(item.ref.human())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(item.ref.human(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Text(item.ref.hebrew(), style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Rtl), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val notes = item.he?.notes.orEmpty() + item.en?.notes.orEmpty()
            if (notes.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                for ((i, n) in notes.withIndex()) Text(
                    "${i + 1}. $n",
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(if (bookmarked) "Remove bookmark" else "Bookmark") },
                leadingContent = { Icon(if (bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null) },
                modifier = Modifier.clickable { vm.toggleBookmark(item, bookmarked) },
            )
            ListItem(
                headlineContent = { Text("Copy with citation") },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(citation))
                    onDismiss()
                },
            )
            ListItem(
                headlineContent = { Text("Share") },
                leadingContent = { Icon(Icons.Outlined.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, citation)
                    context.startActivity(Intent.createChooser(send, null))
                    onDismiss()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySheet(prefs: Prefs, vm: ReaderViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Layout", style = MaterialTheme.typography.titleSmall)
            val layouts = listOf(Layout.Hebrew to "עברית", Layout.English to "English", Layout.Stacked to "Both", Layout.SideBySide to "Side")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                layouts.forEachIndexed { i, (l, label) ->
                    SegmentedButton(
                        selected = prefs.layout == l,
                        onClick = { vm.setLayout(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, layouts.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text("Font size", style = MaterialTheme.typography.titleSmall)
            var scale by remember { mutableStateOf(prefs.fontScale) }
            Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { vm.setFontScale(scale) }, valueRange = 0.7f..2f)
            SwitchRow("Vowels (niqqud)", prefs.niqqud, vm::setNiqqud)
            SwitchRow("Cantillation (te'amim)", prefs.cantillation, vm::setCantillation)
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            val themes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = prefs.theme == t,
                        onClick = { vm.setTheme(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) { Text(t.name, maxLines = 1) }
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
