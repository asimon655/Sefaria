package org.asimon.sefaria.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.asimon.sefaria.data.Line
import org.asimon.sefaria.data.SchemaString
import org.asimon.sefaria.screens.Book
import java.util.Stack

@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable(ContentSerializer::class)
sealed interface Content

@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class Category(
    @JsonNames("heCategory")
    val nameHe: String,
    @JsonNames("category")
    val nameEn: String,
    @JsonNames("heDesc")
    val descHe: String,
    @JsonNames("enDesc")
    val descEn: String,
    @JsonNames("heShortDesc")
    val shortDescHe: String,
    @JsonNames("enShortDesc")
    val shortDescEn: String,
    val contents: List<Content> = listOf()
) : Content


@OptIn(ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
@Serializable
data class Book(
    @JsonNames("heTitle")
    val titleHe: String,
    @JsonNames("title")
    val titleEn: String,
    @JsonNames("heShortDesc")
    val shortDescHe: String,
    @JsonNames("enShortDesc")
    val shortDescEn: String,
    val corpus: String? = null,
    @JsonNames("primary_category")
    val primaryCategory: String? = null,
    val categories: List<String>,
) : Content

private object ContentSerializer : JsonContentPolymorphicSerializer<Content>(Content::class) {
    override fun selectDeserializer(element: JsonElement) = when {
        "category" in element.jsonObject -> Category.serializer()
        "contents" in element.jsonObject -> Category.serializer()
        "nameHe" in element.jsonObject -> Category.serializer()
        "categories" in element.jsonObject -> Book.serializer()
        else -> error("problem with ${element.jsonObject.keys}")
    }
}

fun traverseTableOfContent(tableOfContent: List<Content>): Map<String, Book> {
    val stack = Stack<Content>()
    val map = HashMap<String, Book>()
    stack.addAll(tableOfContent)
    while (stack.isNotEmpty()) {
        when (val content = stack.pop()) {
            is Category -> stack.addAll(content.contents)
            is Book -> map.put(content.titleEn, content)
        }
    }
    return map
}

data class Line(
    val ref: String,
    val text: String,
) {
    // constructor with line you read from file
    constructor(line: String) : this(line.trim().substringBefore('@'),
        line.trim().substringAfter('@'))

    override fun toString(): String {
        return "${this.ref}@${this.text}"
    }
}

sealed interface Schema {
    val ref: String
}

fun Schema(schema: String): Schema {
    return if (schema.substringAfter('@').first() == '[') {
        SchemaString(schema)
    } else {
        SchemaInt(schema)
    }
}

data class SchemaString(
    override val ref: String,
    val subRefs: List<String>,
): Schema {
    // constructor with line you read from file
    constructor(schema: String) : this(schema.trim().substringBefore('@'),
        schema.trim().substringAfter('@').trim('[',']').split(','))

    override fun toString(): String {
        return "${this.ref}@[${this.subRefs.joinToString(",")}]"
    }
}

data class SchemaInt(
    override val ref: String,
    val subRefs: Int,
): Schema {
    // constructor with line you read from file
    constructor(schema: String) : this(schema.trim().substringBefore('@'),
        schema.trim().substringAfter('@').toInt())

    override fun toString(): String {
        return "${this.ref}@${this.subRefs}"
    }
}