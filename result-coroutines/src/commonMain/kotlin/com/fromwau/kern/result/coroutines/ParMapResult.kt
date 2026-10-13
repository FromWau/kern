package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs [transform] on every item in parallel and gives their values in the items' order, or the first error to
 * arrive, cancelling the steps still running or waiting.
 *
 * ```kotlin
 * val forecasts: Result<List<Weather>, DataError> = cities.parMapResult(concurrency = 4) { load(it) }
 * ```
 *
 * A cancelled step is cut wherever it is suspended, and the values of steps that already succeeded are dropped,
 * so use it for steps that are safe to abandon, such as lookups.
 *
 * @param concurrency how many steps run at once, all of them by default; must be at least 1, or this throws an
 *   [IllegalArgumentException].
 * @param transform the step. An exception it throws is thrown on, not made an error.
 */
public suspend fun <T, S, E : ResultError> Iterable<T>.parMapResult(
    concurrency: Int = Int.MAX_VALUE,
    transform: suspend (T) -> Result<S, E>,
): Result<List<S>, E> = try {
    require(concurrency > 0) { "concurrency must be positive, was $concurrency" }
    val gate = if (concurrency == Int.MAX_VALUE) null else Semaphore(concurrency)
    coroutineScope {
        val values = map { item ->
            async {
                val result = if (gate == null) transform(item) else gate.withPermit { transform(item) }
                when (result) {
                    is Result.Success -> result.value
                    // Failing the child is what cancels its siblings and ends the scope with this error.
                    is Result.Error -> throw FirstError(result.error)
                }
            }
        }
        Ok(values.awaitAll())
    }
} catch (first: FirstError) {
    // The scope attaches a sibling's failure during cancellation to the first error, where it would be lost.
    val lost = first.suppressedExceptions.filter { it !is FirstError }
    if (lost.isNotEmpty()) throw lost.first().apply { lost.drop(1).forEach(::addSuppressed) }
    @Suppress("UNCHECKED_CAST")
    Err(first.error as E)
}

/** Carries the first error out of [parMapResult]'s scope. */
private class FirstError(val error: ResultError) : Exception()
