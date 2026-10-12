package org.asimon.sefaria

import kotlinx.serialization.Serializable

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
 * bookmarks) but not a restored reading position.
 */
@Serializable
data class ReaderRoute(
    val book: String,
    val ref: String,
    val highlight: Boolean = true,
    val trail: List<String> = emptyList(), // encoded TrailSteps (core/Trail.kt) that led here
)

/** Full-text results for [q], optionally within [book]. */
@Serializable
data class SearchRoute(val q: String, val book: String? = null)

@Serializable
data object HomeRoute

@Serializable
data object SavedRoute

@Serializable
data object SettingsRoute
