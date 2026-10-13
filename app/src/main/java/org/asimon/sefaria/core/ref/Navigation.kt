package org.asimon.sefaria.core.ref

/*
 * Section navigation computed from the book schema alone, so the reader can page
 * offline. A section is the level directly above segments; empty sections have no
 * slots and are skipped naturally.
 */

/** The section that contains a slot. */
fun Book.sectionAt(slot: Int): Ref = Ref.at(this, slot).section()

/** The section to open for any ref: the one containing its first slot. Throws [RefException]. */
fun Ref.openSection(): Ref = book.sectionAt(slots().first)

fun Book.nextSection(section: Ref): Ref? {
    val end = section.slots().last + 1
    return if (end < slots) sectionAt(end) else null
}

fun Book.prevSection(section: Ref): Ref? {
    val start = section.slots().first
    return if (start > 0) sectionAt(start - 1) else null
}

/** The segment ref with a displayed segment number within a section (numbering offsets undone). */
fun Ref.segment(number: Int): Ref =
    copy(sections = sections + (number - 1 - node.offset(sections.size, sections)), to = null)

/**
 * A ref for slots [start, end): a segment, a whole section when the interval is exactly one,
 * or a segment range; an interval that crosses schema nodes is cut at the end of the first.
 * Mirrors the server's ref.FromSlots.
 */
fun Book.fromSlots(start: Int, end: Int): Ref {
    val s = Ref.at(this, start)
    if (end - start <= 1) return s
    val sec = s.section()
    if (sec.sections.isNotEmpty() || sec.node.isJagged) {
        val r = runCatching { sec.slots() }.getOrNull()
        if (r != null && r.first == start && r.last + 1 == end) return sec
    }
    var e = Ref.at(this, end - 1)
    if (e.node !== s.node) e = Ref.at(this, s.node.slotEnd - 1)
    return s.copy(to = e.sections)
}
