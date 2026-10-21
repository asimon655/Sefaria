package org.asimon.sefaria.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A step of the source trail: a place the reader came from by following connections. A trail
 * lists them oldest first (e.g. Genesis 1:2 → Rashi on Genesis 1:2:1 → Chagigah 12a); reader
 * routes and history rows carry it as JSON ([encode]).
 */
@Serializable
data class TrailStep(val book: String, val ref: String, val en: String, val he: String, val category: String = "") {
    companion object {
        private val list = ListSerializer(serializer())

        /** A trail as JSON; "" for none. */
        fun encode(trail: List<TrailStep>): String = if (trail.isEmpty()) "" else Json.encodeToString(list, trail)

        /** A trail from [encode]; empty for "" or anything unreadable. */
        fun decode(s: String): List<TrailStep> =
            if (s.isEmpty()) emptyList() else runCatching { Json.decodeFromString(list, s) }.getOrDefault(emptyList())
    }
}
