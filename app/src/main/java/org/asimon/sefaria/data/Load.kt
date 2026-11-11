package org.asimon.sefaria.data

import org.asimon.sefaria.data.remote.ApiError

/** A loaded value, or an error to show. [offline] marks a cached value served because the network failed. */
sealed interface Load<out T> {
    data class Ok<T>(val value: T, val offline: Boolean = false) : Load<T>
    data class Err(val error: AppError) : Load<Nothing>
}

/**
 * Errors the UI can show; it turns them into localized text (ui/components/Locale.kt).
 *
 * This is the one place that says what an error means for what happens next ([transient]):
 * the cache, search and the downloads all ask it, so they agree on when to wait and try again,
 * when to answer from what is on the phone, and when to give up.
 */
sealed interface AppError {
    data object Network : AppError
    /** The server does not know this book or ref (404, or a ref it can't read). [detail] is the server's English message. */
    data class NotFound(val detail: String) : AppError
    /** The server refused the request as it was asked (400): a query too long, a text too large. */
    data class Rejected(val detail: String) : AppError
    /** The server is busy or limiting this client (429, or 503 search_busy); try again in [retryAfter] seconds. */
    data class Busy(val retryAfter: Int = 1) : AppError
    /** A search the server gave up on after its time limit: a narrower one may work. */
    data object SearchTimeout : AppError
    data class Server(val status: Int, val detail: String) : AppError
    data class BadResponse(val detail: String) : AppError
    data object NoText : AppError
    /** A word* search with no connection: offline search doesn't do prefixes. */
    data object PrefixOffline : AppError
    /** The phone has no room for a download: [needed] bytes against [free]. */
    data class Storage(val needed: Long, val free: Long) : AppError

    /**
     * Whether the same request may succeed later without anything changing on the phone: no
     * connection, a busy or failing server. Then a cached or downloaded copy is the answer for
     * now, and a download stays queued. The rest (not found, rejected, a response this app
     * can't read, no room) fail the same way however often they are tried.
     */
    val transient: Boolean
        get() = this is Network || this is Busy || this is SearchTimeout || this is Server
}

fun ApiError.toAppError(): AppError = when (this) {
    is ApiError.Network -> AppError.Network
    is ApiError.BadResponse -> AppError.BadResponse(message)
    is ApiError.Http -> when {
        status == 429 || code == "rate_limited" || code == "search_busy" -> AppError.Busy(retryAfter ?: 1)
        code == "search_failed" -> AppError.SearchTimeout
        status == 404 || code == "bad_ref" -> AppError.NotFound(message)
        status in 400..499 -> AppError.Rejected(message)
        else -> AppError.Server(status, message)
    }
}

inline fun <T, R> Load<T>.map(f: (T) -> R): Load<R> = when (this) {
    is Load.Ok -> Load.Ok(f(value), offline)
    is Load.Err -> this
}
