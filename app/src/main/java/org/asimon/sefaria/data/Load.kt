package org.asimon.sefaria.data

import org.asimon.sefaria.data.remote.ApiError

/** A loaded value, or an error to show. [offline] marks a cached value served because the network failed. */
sealed interface Load<out T> {
    data class Ok<T>(val value: T, val offline: Boolean = false) : Load<T>
    data class Err(val error: AppError) : Load<Nothing>
}

/** Errors the UI can show; it turns them into localized text (ui/components/Errors.kt). */
sealed interface AppError {
    data object Network : AppError
    /** The server does not know this book or ref (400/404). [detail] is the server's English message. */
    data class NotFound(val detail: String) : AppError
    data class Server(val status: Int, val detail: String) : AppError
    data class BadResponse(val detail: String) : AppError
    data object NoText : AppError
}

fun ApiError.toAppError(): AppError = when (this) {
    is ApiError.Network -> AppError.Network
    is ApiError.Http -> if (status == 400 || status == 404) AppError.NotFound(message) else AppError.Server(status, message)
    is ApiError.BadResponse -> AppError.BadResponse(message)
}

inline fun <T, R> Load<T>.map(f: (T) -> R): Load<R> = when (this) {
    is Load.Ok -> Load.Ok(f(value), offline)
    is Load.Err -> this
}
