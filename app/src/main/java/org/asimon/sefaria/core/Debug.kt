package org.asimon.sefaria.core

import org.asimon.sefaria.BuildConfig

/*
 * Debug assertions: checks of what the code assumes about its state and data, which should
 * never fail. In a debug build (and in the JVM tests, which run the debug variant) a failed one
 * throws an AssertionError that says what was assumed; a release build has none of them
 * (BuildConfig.DEBUG is a constant there, and R8 removes the dead branches).
 *
 * An assertion states an invariant. It is not error handling: a response the server may really
 * send, or a file that may really be damaged, gets an error the user can see, not an assertion.
 * (Kotlin's own assert() is no use here: Android never enables JVM assertions.)
 */

/** Throws in a debug build when [condition] is false; [message] says what was assumed. */
inline fun debugAssert(condition: Boolean, message: () -> String) {
    if (BuildConfig.DEBUG && !condition) throw AssertionError("assertion failed: " + message())
}

/** Runs [block] in a debug build only: for a check that costs something (a loop, a lookup). */
inline fun debugOnly(block: () -> Unit) {
    if (BuildConfig.DEBUG) block()
}
