package org.asimon.sefaria.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** JSON DTOs for the /v1 API (SERVER_SPEC §6). Field names follow the server. */

val SefariaJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true // null -> default for non-nullable fields
    explicitNulls = false
}

@Serializable
data class Bilingual(val en: String = "", val he: String = "")

@Serializable
data class ManifestDto(
    @SerialName("build_id") val buildId: String,
    @SerialName("dump_date") val dumpDate: String = "",
    val format: Int = 0,
    val books: Int = 0,
)

@Serializable
data class TocDto(
    @SerialName("build_id") val buildId: String = "",
    val tree: List<TocNodeDto> = emptyList(),
)

@Serializable
data class TocNodeDto(
    val kind: String,
    val en: String = "",
    val he: String = "",
    val title: String = "", // books only
    val desc: Bilingual = Bilingual(),
    val short: Bilingual = Bilingual(),
    val children: List<TocNodeDto> = emptyList(),
    /** Books only: a commentary or targum (the server's search.IsDependent), searched after the texts. */
    val dependent: Boolean = false,
) {
    val isBook get() = kind == "book"
}

@Serializable
data class BookDto(
    val title: String,
    val he: String = "",
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    @SerialName("he_categories") val heCategories: List<String> = emptyList(),
    val dependence: String = "",
    @SerialName("base_text_titles") val baseTextTitles: List<String> = emptyList(),
    @SerialName("collective_title") val collectiveTitle: String = "",
    val authors: List<String> = emptyList(), // slugs
    @SerialName("author_names") val authorNames: List<Bilingual> = emptyList(),
    val desc: Bilingual = Bilingual(),
    @SerialName("short_desc") val shortDesc: Bilingual = Bilingual(),
    val schema: NodeDto,
    // A struct with no nodes comes as null ("Daf Yomi": null); coerceInputValues doesn't reach map values.
    @SerialName("alt_structs") private val altStructsRaw: Map<String, List<AltNodeDto>?> = emptyMap(),
    val slots: Int = 0,
    val versions: List<VersionDto> = emptyList(),
) {
    /** The alternate structures that have nodes. */
    val altStructs: Map<String, List<AltNodeDto>> get() = buildMap { for ((k, v) in altStructsRaw) if (!v.isNullOrEmpty()) put(k, v) }
}

@Serializable
data class NodeDto(
    val key: String = "",
    val en: String = "",
    val he: String = "",
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
    val default: Boolean = false,
    val children: List<NodeDto>? = null,
    @SerialName("address_types") val addressTypes: List<String> = emptyList(),
    @SerialName("section_names") val sectionNames: List<String> = emptyList(),
    val shape: JsonElement? = null, // a number at the segment level, else nested arrays with nulls
    val offsets: OffsetsDto? = null,
    @SerialName("slot_start") val slotStart: Int = 0,
    @SerialName("slot_end") val slotEnd: Int = 0,
)

/** Numbering offsets (Zohar-style continuous paragraph numbers); see Node.offset. */
@Serializable
data class OffsetsDto(val top: Int = 0, val sub: List<Int> = emptyList())

@Serializable
data class AltNodeDto(
    val en: String = "",
    val he: String = "",
    val ref: String = "",
    val refs: List<String> = emptyList(),
    val children: List<AltNodeDto> = emptyList(),
)

@Serializable
data class VersionDto(
    val id: Int,
    val lang: String,
    val title: String = "",
    val he: String = "",
    val license: String = "",
    val source: String = "",
    val default: Boolean = false,
    val filled: Int = 0,
)

@Serializable
data class TextDto(
    val ref: String,
    @SerialName("he_ref") val heRef: String = "",
    val book: String = "",
    val versions: Map<String, VersionDto> = emptyMap(),
    val sections: List<SectionDto> = emptyList(),
    val prev: String? = null,
    val next: String? = null,
)

@Serializable
data class SectionDto(
    val ref: String,
    @SerialName("he_ref") val heRef: String = "",
    val first: Int = 1,
    val he: List<String> = emptyList(),
    val en: List<String> = emptyList(),
    /** Links touching each segment (builds with links). */
    @SerialName("link_counts") val linkCounts: List<Int> = emptyList(),
)

@Serializable
data class LinksDto(val ref: String, val groups: List<LinkGroupDto> = emptyList())

@Serializable
data class LinkGroupDto(
    val category: String,
    val en: String = "",
    val he: String = "",
    val count: Int = 0,
    val books: List<LinkBookDto> = emptyList(),
)

@Serializable
data class LinkBookDto(
    val title: String,
    val he: String = "",
    val collective: String = "", // "Rashi" for every "Rashi on …"; empty for books without one
    val count: Int = 0,
    val links: List<LinkDto> = emptyList(),
) {
    /** What a pin refers to: the commentator, so a pin follows it from book to book. */
    val pinKey get() = collective.ifEmpty { title }
}

@Serializable
data class LinkDto(
    val anchor: String,
    val ref: String,
    val human: String = "",
    @SerialName("he_ref") val heRef: String = "",
    val type: String = "",
    val he: List<String> = emptyList(),
    val en: List<String> = emptyList(),
)

@Serializable
data class ResolveDto(
    val ref: String,
    @SerialName("he_ref") val heRef: String = "",
    val human: String = "",
    val book: String = "",
    val kind: String = "",
)

@Serializable
data class CompleteDto(val items: List<CompletionDto> = emptyList())

/** [kind] is "ref" (the query parsed as a ref) or "book". */
@Serializable
data class CompletionDto(
    val kind: String,
    val ref: String,
    val title: String = "",
    val he: String = "",
    val book: String = "",
)

/** [forms]: each query word's forms that it also matched; [total]: ranked candidates scored (SERVER_SPEC §6.6). */
@Serializable
data class SearchDto(
    val lang: String = "",
    val hits: List<SearchHitDto> = emptyList(),
    val forms: Map<String, List<String>> = emptyMap(),
    val total: Int = 0,
    val next: String? = null,
)

/** [snippet] is plain text with the matching words in <b>; [match] is exact, prefix or form. */
@Serializable
data class SearchHitDto(
    val ref: String,
    val human: String = "",
    @SerialName("he_ref") val heRef: String = "",
    val book: String = "",
    val snippet: String = "",
    val match: String = "exact",
)

/** One day of learning schedules (SERVER_SPEC §6.7). */
@Serializable
data class CalendarDto(
    val date: String = "",
    val diaspora: Boolean = true,
    val custom: String = "",
    val items: List<CalendarItemDto> = emptyList(),
)

@Serializable
data class CalendarRefDto(
    val ref: String,
    val human: String = "",
    @SerialName("he_ref") val heRef: String = "",
    val book: String = "",
)

/**
 * A schedule's learning: [ref] is the first of [refs] (empty when there is one). [date] is set when
 * the item belongs to another day of the week (the coming Shabbat's parasha); [note] names a special Shabbat.
 */
@Serializable
data class CalendarItemDto(
    val id: String,
    val title: Bilingual = Bilingual(),
    val display: Bilingual = Bilingual(),
    val date: String? = null,
    val note: Bilingual? = null,
    val ref: String,
    val human: String = "",
    @SerialName("he_ref") val heRef: String = "",
    val book: String = "",
    val refs: List<CalendarRefDto> = emptyList(),
    val aliyot: List<CalendarRefDto> = emptyList(),
)

@Serializable
data class ErrorDto(val error: ErrorBody) {
    @Serializable
    data class ErrorBody(val code: String = "", val message: String = "", val suggestions: List<String> = emptyList())
}

/** The library of a build (/v1/library, SERVER_SPEC §7): files named by their sha256. */
@Serializable
data class LibraryDto(
    val format: Int = 0,
    @SerialName("build_id") val buildId: String = "",
    val dict: LibraryFileDto,
    val books: List<LibraryFileDto> = emptyList(),
    val links: LibraryFileDto? = null,
    /** The Hebrew forms table (zlib), for offline search. */
    val forms: LibraryFileDto? = null,
)

/**
 * A file of the library, downloaded from /v1/library/<file>; [book] is set for book files.
 * The name is the file's sha256 and an extension.
 */
@Serializable
data class LibraryFileDto(
    val book: String = "",
    val he: String = "",
    val file: String,
    val bytes: Long,
    /** A book's index file (.sfi), downloaded with it for offline search. */
    val index: String = "",
    @SerialName("index_bytes") val indexBytes: Long = 0,
) {
    val sha256 get() = file.substringBefore('.')

    /** The book's index file as a file to download, if it has one. */
    val indexFile get() = if (index.isEmpty()) null else LibraryFileDto(book = book, he = he, file = index, bytes = indexBytes)
}
