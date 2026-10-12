package com.fromwau.kern.logger

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What the logger does when a [LogSink] throws an `Exception`. An `Error` such as `OutOfMemoryError` always
 * propagates.
 */
public sealed interface SinkFailure {
    /**
     * The entry is lost to that sink alone, and counted in [Logger.health]. The other sinks, the file and the
     * console still get it, and the code that logged never sees the failure.
     *
     * @param breaker whether a sink that keeps failing is skipped for a while, so one that times out on every
     *   call cannot slow every log line down.
     */
    public data class Drop(val breaker: Breaker = Breaker.Trip()) : SinkFailure

    /**
     * Throws the sink's exception to the code that logged, after the other sinks, the file and the console have
     * the entry. For tests, where a broken sink should fail loudly.
     */
    public data object Throw : SinkFailure
}

/** When [SinkFailure.Drop] stops calling a sink that keeps failing. */
public sealed interface Breaker {
    /** Every entry is offered to the sink, however often it fails. */
    public data object Off : Breaker

    /**
     * After [consecutiveFailures] failures in a row the sink is skipped, and its entries counted as dropped,
     * until [cooldown] has passed. The next entry then tries again: a success closes the breaker, a failure opens
     * it for another [cooldown].
     */
    public data class Trip(
        val consecutiveFailures: Int = 10,
        val cooldown: Duration = 60.seconds,
    ) : Breaker {
        init {
            require(consecutiveFailures > 0) { "consecutiveFailures must be positive, was $consecutiveFailures" }
            require(!cooldown.isNegative()) { "cooldown must not be negative, was $cooldown" }
        }
    }
}
