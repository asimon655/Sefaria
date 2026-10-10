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

/** The segment ref at a 1-based segment number within a section. */
fun Ref.segment(number: Int): Ref = copy(sections = sections + (number - 1), to = null)
