package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Each value of this flow as a success; an exception of the type [caught] names that the flow throws becomes one last
 * error, and the flow completes. It adapts a third-party flow that reports a failure by throwing:
 *
 * ```kotlin
 * val weather: Flow<Result<Weather, NetworkError>> =
 *     repository.observe(city).asResult(IOException::class) { NetworkError.Unknown(it) }
 * ```
 *
 * A throw of any other type is not caught: it ends the flow with that exception. Cancellation is handled as in
 * [suspendCatching]. Exceptions thrown downstream of this flow are never caught. To reconnect a flow that drops, put
 * `retry` or `retryWhen` before this, where the failure is still an exception.
 *
 * @param caught the type of exception made an error, along with its subtypes.
 * @param toError makes your error from the exception the flow threw. A throw from this block is not caught.
 */
public fun <X : Exception, S, E : ResultError> Flow<S>.asResult(
    caught: KClass<X>,
    toError: (X) -> E,
): Flow<Result<S, E>> = map<S, Result<S, E>> { Ok(it) }.catch { thrown ->
    if (!currentCoroutineContext().isActive) throw thrown
    emit(Err(toError(caught.safeCast(thrown) ?: throw thrown)))
}

/**
 * Each success of this flow transformed by [transform], which may suspend; errors pass through unchanged. An
 * exception from [transform] is thrown on, not made an error.
 */
public fun <S, T, E : ResultError> Flow<Result<S, E>>.mapSuccess(transform: suspend (S) -> T): Flow<Result<T, E>> =
    map { result -> result.map { transform(it) } }

/**
 * Each success of this flow run through [transform], a step that may suspend and may fail, its result taking the
 * success's place; errors pass through unchanged. One item comes out for each that goes in: unlike a flow's own
 * `flatMapConcat`, nothing is flattened. An exception from [transform] is thrown on, not made an error.
 *
 * ```kotlin
 * prices.flatMapSuccess { enrich(it) }   // Flow<Result<Quote, FeedError>>
 * ```
 */
public fun <S, T, E : ResultError> Flow<Result<S, E>>.flatMapSuccess(
    transform: suspend (S) -> Result<T, E>,
): Flow<Result<T, E>> = map { result -> result.flatMap { transform(it) } }
