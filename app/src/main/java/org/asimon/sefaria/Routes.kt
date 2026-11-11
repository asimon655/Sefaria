package org.asimon.sefaria

import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavType
import kotlinx.serialization.Serializable
import org.asimon.sefaria.core.TrailStep
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Type-safe navigation routes. */

/** A level of the library tree; [path] is the category names joined by [SEP] ("" = top). */
@Serializable
data class LibraryRoute(val path: String = "") {
    val names get() = if (path.isEmpty()) emptyList() else path.split(SEP)

    fun child(name: String) = LibraryRoute(if (path.isEmpty()) name else path + SEP + name)

    companion object {
        const val SEP = "|"
    }
}

@Serializable
data class BookRoute(val title: String)

/**
 * The reader for [book], opened at [ref] (any ref form the server returns: book, node,
 * section, segment or range). [highlight] marks the target segment (search results,
 * bookmarks) but not a restored reading position. [words] (a search result's, SearchMarks):
 * the words marked in the target segment in its place, when it holds them.
 */
@Serializable
data class ReaderRoute(
    val book: String,
    val ref: String,
    val highlight: Boolean = true,
    val words: List<String> = emptyList(),
    val trail: List<TrailStep> = emptyList(), // the steps that led here (core/Trail.kt)
) {
    companion object {
        /** How navigation carries the trail: as JSON. Pass it where the route is registered and read. */
        val typeMap: Map<KType, NavType<*>> = mapOf(typeOf<List<TrailStep>>() to TrailNavType)
    }
}

private object TrailNavType : NavType<List<TrailStep>>(isNullableAllowed = false) {
    override fun put(bundle: Bundle, key: String, value: List<TrailStep>) = bundle.putString(key, TrailStep.encode(value))
    override fun get(bundle: Bundle, key: String) = TrailStep.decode(bundle.getString(key).orEmpty())
    override fun parseValue(value: String) = TrailStep.decode(value)
    override fun serializeAsValue(value: List<TrailStep>): String = Uri.encode(TrailStep.encode(value))
}

/** The search tab: the field, with suggestions as you type (refs, books, places in books). */
@Serializable
data object SearchTabRoute

/**
 * Full-text results for [q], optionally within [book] or within [scope]: the books and
 * categories chosen before searching, as Scope.encode writes them ("": the whole library).
 */
@Serializable
data class SearchRoute(val q: String, val book: String? = null, val scope: String = "")

@Serializable
data object HomeRoute

@Serializable
data object SavedRoute

@Serializable
data object SettingsRoute
