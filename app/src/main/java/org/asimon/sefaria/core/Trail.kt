package org.asimon.sefaria.core

/**
 * The source trail: the places a reader came from by following connections, oldest first
 * (e.g. Genesis 1:2 → Rashi on Genesis 1:2:1 → Chagigah 12a). Each step is kept as one
 * string so routes and history rows can carry the trail as plain strings.
 */
data class TrailStep(val book: String, val ref: String, val en: String, val he: String) {
    fun encode() = listOf(book, ref, en, he).joinToString(UNIT.toString())

    companion object {
        private const val UNIT = '\u001F' // between fields of a step
        private const val RECORD = '\u001E' // between steps in a history row

        fun decode(s: String): TrailStep? = s.split(UNIT).takeIf { it.size == 4 }?.let { TrailStep(it[0], it[1], it[2], it[3]) }

        fun decodeAll(steps: List<String>): List<TrailStep> = steps.mapNotNull(::decode)

        /** A route's trail as one history column. */
        fun join(steps: List<String>) = steps.joinToString(RECORD.toString())

        fun split(column: String): List<String> = if (column.isEmpty()) emptyList() else column.split(RECORD)
    }
}
