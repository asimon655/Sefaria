package org.asimon.sefaria.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class Layout { Hebrew, English, Stacked, SideBySide }
enum class ThemeMode { System, Light, Stone, Sepia, EInk, Dark, Black }

/** Verse per line, or verses run together as a paragraph (single-language layouts only). */
enum class TextFlow { Lines, Paragraph }

data class Prefs(
    val serverUrl: String,
    val layout: Layout = Layout.Hebrew,
    val fontScale: Float = 1f,
    val niqqud: Boolean = true,
    val cantillation: Boolean = true,
    val theme: ThemeMode = ThemeMode.System,
    val flow: TextFlow = TextFlow.Lines,
    val keepScreenOn: Boolean = false,
    /** While connections are open, scrolling settles with a verse at the top (feature/reader/Snap.kt). */
    val snapToVerse: Boolean = true,
    /** Commentators whose text shows directly in the connections panel, in pin order (collective titles). */
    val pinned: List<String> = emptyList(),
    val onboarded: Boolean = true, // until the store is read; then the stored value
)

/** User preferences (DataStore). [prefs] is hot so composables and view models read it synchronously. */
class Settings(private val store: DataStore<Preferences>, scope: CoroutineScope, private val defaultUrl: String) {
    private object K {
        val serverUrl = stringPreferencesKey("server_url")
        val layout = stringPreferencesKey("layout")
        val fontScale = floatPreferencesKey("font_scale")
        val niqqud = booleanPreferencesKey("niqqud")
        val cantillation = booleanPreferencesKey("cantillation")
        val theme = stringPreferencesKey("theme")
        val flow = stringPreferencesKey("flow")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val snapToVerse = booleanPreferencesKey("snap_to_verse")
        val pinned = stringPreferencesKey("pinned") // titles joined by '\n'
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val prefs: StateFlow<Prefs> = store.data.map { p ->
        Prefs(
            serverUrl = p[K.serverUrl] ?: defaultUrl,
            layout = p[K.layout]?.let { runCatching { Layout.valueOf(it) }.getOrNull() } ?: Layout.Hebrew,
            fontScale = p[K.fontScale] ?: 1f,
            niqqud = p[K.niqqud] ?: true,
            cantillation = p[K.cantillation] ?: true,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            flow = p[K.flow]?.let { runCatching { TextFlow.valueOf(it) }.getOrNull() } ?: TextFlow.Lines,
            keepScreenOn = p[K.keepScreenOn] ?: false,
            snapToVerse = p[K.snapToVerse] ?: true,
            pinned = p[K.pinned]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty(),
            onboarded = p[K.onboarded] ?: false,
        )
    }.stateIn(scope, SharingStarted.Eagerly, Prefs(defaultUrl))

    /** The stored server URL, read from disk (not the initial placeholder of [prefs]). */
    suspend fun serverUrl(): String = store.data.first()[K.serverUrl] ?: defaultUrl

    suspend fun setServerUrl(url: String) = store.edit {
        if (url.isBlank()) it.remove(K.serverUrl) else it[K.serverUrl] = url.trim()
    }
    suspend fun setLayout(v: Layout) = store.edit { it[K.layout] = v.name }
    suspend fun setFontScale(v: Float) = store.edit { it[K.fontScale] = v.coerceIn(0.7f, 2f) }
    suspend fun setNiqqud(v: Boolean) = store.edit { it[K.niqqud] = v }
    suspend fun setCantillation(v: Boolean) = store.edit { it[K.cantillation] = v }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[K.theme] = v.name }
    suspend fun setFlow(v: TextFlow) = store.edit { it[K.flow] = v.name }
    suspend fun setKeepScreenOn(v: Boolean) = store.edit { it[K.keepScreenOn] = v }
    suspend fun setSnapToVerse(v: Boolean) = store.edit { it[K.snapToVerse] = v }
    suspend fun setOnboarded() = store.edit { it[K.onboarded] = true }

    /** Pins or unpins a commentator (by collective title). */
    suspend fun togglePin(key: String) = store.edit { p ->
        val cur = p[K.pinned]?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        p[K.pinned] = (if (key in cur) cur - key else cur + key).joinToString("\n")
    }
}
