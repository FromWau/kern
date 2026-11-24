package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Runs [block], which may suspend, and gives its value as a success, or an exception of the type [caught] names as an
 * error. It adapts third-party code that reports a failure by throwing, such as an HTTP client, to code that returns
 * results:
 *
 * ```kotlin
 * suspend fun fetch(city: String): Result<Weather, NetworkError> =
 *     suspendCatching(IOException::class, { NetworkError.Unknown(it) }) { http.get(city) }
 * ```
 *
 * Name the type the code you call documents for its failure, as narrow as it is. A throw of any other type is not
 * caught: it propagates with its own stack trace.
 *
 * Once the coroutine is cancelled, what the block throws is thrown on, never made an error, even when [caught] names
 * a supertype of it. A timeout inside the block, while the coroutine is still active, is an error when [caught]
 * names its type or a supertype, such as `CancellationException`.
 *
 * @param caught the type of exception made an error, along with its subtypes.
 * @param toError makes your error from the exception [block] threw. A throw from this block is not caught.
 * @param block the work that may throw.
 */
public suspend inline fun <X : Exception, S, E : ResultError> suspendCatching(
    caught: KClass<X>,
    toError: (X) -> E,
    block: () -> S,
): Result<S, E> = try {
    Ok(block())
} catch (e: Exception) {
    // Cancelled, this coroutine ends with what it threw, as it would without suspendCatching.
    if (!currentCoroutineContext().isActive) throw e
    Err(toError(caught.safeCast(e) ?: throw e))
}
