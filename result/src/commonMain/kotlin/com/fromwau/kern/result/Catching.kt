package com.fromwau.kern.result

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] and gives its value as a success, or an exception it throws as an error, so code that throws
 * joins code that returns results:
 *
 * ```kotlin
 * fun loadUser(id: Long): Result<User, CrudError> = catching({ CrudError.DbError(it) }) { dao.load(id) }
 * ```
 *
 * [block] cannot suspend; in suspending code use `suspendCatching` from `result-coroutines`. Unlike that, which makes a
 * timeout in a coroutine still running an error, this rethrows every [CancellationException].
 *
 * @param toError makes your error from the [Exception] [block] threw. A [CancellationException] is rethrown
 *   instead, and an [Error], such as running out of memory, is never caught.
 * @param block the work that may throw.
 */
public fun <S, E : ResultError> catching(
    toError: (Exception) -> E,
    block: () -> S,
): Result<S, E> = try {
    Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Err(toError(e))
}
