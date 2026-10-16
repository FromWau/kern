package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok

/** Each name's counts summed into one, in the order each name first comes. */
internal fun sumByName(counts: List<Pair<String, Long>>): Map<String, Long> =
    counts.groupBy({ it.first }, { it.second }).mapValues { (_, all) -> all.sum() }

/**
 * Each name's [Pending]s summed into one, in the order each name first comes: [Pending.TimedOut] when all timed out,
 * otherwise [Pending.GaveUp] with the error of the first that gave up.
 */
internal fun pendingByName(pending: List<Pair<String, Pending>>): Map<String, Pending> =
    pending.groupBy({ it.first }, { it.second }).mapValues { (_, all) ->
        val count = all.sumOf { it.count }
        all.firstNotNullOfOrNull { it as? Pending.GaveUp }?.copy(count = count) ?: Pending.TimedOut(count)
    }

/** `Ok` when no name in [errors] has any, or [CloseError] with each name's errors folded into one list. */
internal fun closeResult(errors: List<Pair<String, List<SinkError>>>): EmptyResult<CloseError> {
    val failed = errors
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, lists) -> foldCloseErrors(lists.flatten()) }
        .filterValues { it.isNotEmpty() }
    return if (failed.isEmpty()) Ok(Unit) else Err(CloseError(failed))
}

/** One [SinkError.TimedOut] and one [SinkError.Lost], each summed, every other error once, Lost last. */
private fun foldCloseErrors(errors: List<SinkError>): List<SinkError> {
    val timedOut = SinkError.TimedOut(errors.filterIsInstance<SinkError.TimedOut>().sumOf { it.pending })
    val lost = errors.filterIsInstance<SinkError.Lost>().sumOf { it.count }
    val failures = errors
        .filterNot { it is SinkError.Lost }
        .map { if (it is SinkError.TimedOut) timedOut else it }
        .distinct()
    return failures + listOfNotNull(SinkError.Lost(lost).takeIf { lost > 0 })
}
