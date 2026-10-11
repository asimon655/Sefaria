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
enum class ThemeMode { System, Light, Sepia, Dark }

data class Prefs(
    val serverUrl: String,
    val layout: Layout = Layout.Hebrew,
    val fontScale: Float = 1f,
    val niqqud: Boolean = true,
    val cantillation: Boolean = true,
    val theme: ThemeMode = ThemeMode.System,
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
    }

    val prefs: StateFlow<Prefs> = store.data.map { p ->
        Prefs(
            serverUrl = p[K.serverUrl] ?: defaultUrl,
            layout = p[K.layout]?.let { runCatching { Layout.valueOf(it) }.getOrNull() } ?: Layout.Hebrew,
            fontScale = p[K.fontScale] ?: 1f,
            niqqud = p[K.niqqud] ?: true,
            cantillation = p[K.cantillation] ?: true,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
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
}
