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
) {
    val isBook get() = kind == "book"
}

@Serializable
data class BookDto(
    val title: String,
    val he: String = "",
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val dependence: String = "",
    @SerialName("base_text_titles") val baseTextTitles: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val desc: Bilingual = Bilingual(),
    @SerialName("short_desc") val shortDesc: Bilingual = Bilingual(),
    val schema: NodeDto,
    @SerialName("alt_structs") val altStructs: Map<String, List<AltNodeDto>> = emptyMap(),
    val slots: Int = 0,
    val versions: List<VersionDto> = emptyList(),
)

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
    @SerialName("slot_start") val slotStart: Int = 0,
    @SerialName("slot_end") val slotEnd: Int = 0,
)

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
data class ErrorDto(val error: ErrorBody) {
    @Serializable
    data class ErrorBody(val code: String = "", val message: String = "", val suggestions: List<String> = emptyList())
}
