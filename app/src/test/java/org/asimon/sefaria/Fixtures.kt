package org.asimon.sefaria

/** Reads src/test/resources/fixtures/<name>: JSON recorded from the real server, plus the shared ref vectors. */
fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
