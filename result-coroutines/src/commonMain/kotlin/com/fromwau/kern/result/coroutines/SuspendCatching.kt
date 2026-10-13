package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Runs [block], which may suspend, and gives its value as a success, or an exception it throws as an error:
 *
 * ```kotlin
 * suspend fun fetch(city: String): Result<Weather, NetworkError> =
 *     suspendCatching({ NetworkError.Unknown(it) }) { http.get(city) }
 * ```
 *
 * Once the coroutine is cancelled, what the block throws is thrown on, never made an error. A timeout inside the
 * block, while the coroutine is still active, is an error like any other.
 *
 * @param toError makes your error from the [Exception] [block] threw. An [Error] is never caught.
 * @param block the work that may throw.
 */
public suspend inline fun <S, E : ResultError> suspendCatching(
    toError: (Exception) -> E,
    block: () -> S,
): Result<S, E> = try {
    Ok(block())
} catch (e: Exception) {
    // Cancelled, this coroutine ends with what it threw, as it would without suspendCatching.
    if (!currentCoroutineContext().isActive) throw e
    Err(toError(e))
}
