package com.fromwau.kern.logger

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How a [RetryBuffer] holds what its destination refuses. */
internal data class RetryRule(
    val capacity: Int,
    val drop: DropPolicy,
    val backoff: Duration,
) {
    companion object {
        /** Holds nothing and tries every entry: a refused one is lost and counted. */
        val NONE = RetryRule(capacity = 0, drop = DropPolicy.OLDEST, backoff = Duration.ZERO)
    }
}

/** Entries a destination refused and that were dropped before it recovered. */
internal data class Gap(val count: Long, val from: Instant, val to: Instant) {
    fun span(): String = if (from == to) "at $from" else "between $from and $to"
}

internal fun entries(count: Long): String = if (count == 1L) "1 entry" else "$count entries"

/**
 * Sends items to a destination that may refuse them, holding what it refuses up to a bound and sending the
 * held ones first, in order, once a retry is due. Not thread-safe: its owner serializes the calls.
 */
internal class RetryBuffer<T>(
    private val timeSource: TimeSource,
    private val timestampOf: (T) -> Instant,
) {
    private val pending = ArrayDeque<T>()
    private var retryAt: TimeMark? = null
    private var gap: Gap? = null

    var failures: Long = 0
        private set
    var dropped: Long = 0
        private set
    var lastError: Throwable? = null
        private set

    val health: BufferHealth
        get() = BufferHealth(failures, pending.size, dropped, lastError)

    /** True while nothing is held, nothing was dropped unannounced, and no retry is waiting. */
    val idle: Boolean
        get() = pending.isEmpty() && gap == null && retryAt == null

    /**
     * Sends [item] after everything held, or holds it while a retry is not yet due.
     *
     * @return the exception this attempt failed with, or null when it succeeded or was deferred.
     */
    fun send(item: T, rule: RetryRule, write: (T) -> Unit, writeGap: (Gap) -> Unit): Exception? {
        // A rule that holds nothing tries at once, even under the backoff of a rule it replaced.
        val due = rule.capacity == 0 || retryAt?.hasPassedNow() ?: true
        if (!due) {
            hold(item, rule)
            return null
        }
        return try {
            drain(rule.drop, write, writeGap)
            write(item)
            retryAt = null
            null
        } catch (e: Exception) {
            failures++
            lastError = e
            retryAt = timeSource.markNow() + rule.backoff
            hold(item, rule)
            e
        }
    }

    /**
     * Makes one last attempt at everything held, regardless of the backoff, and gives up on what is still refused.
     *
     * @return how many entries were lost and why, or null when nothing was.
     */
    fun flushOrAbandon(drop: DropPolicy, write: (T) -> Unit, writeGap: (Gap) -> Unit): Pair<Long, Exception>? {
        if (pending.isEmpty() && gap == null) return null
        return try {
            drain(drop, write, writeGap)
            retryAt = null
            null
        } catch (e: Exception) {
            failures++
            lastError = e
            val lost = pending.size + (gap?.count ?: 0)
            dropped += pending.size
            pending.clear()
            gap = null
            retryAt = null
            lost to e
        }
    }

    // The gap stands where the dropped entries were: before the held ones when the oldest gave way, after them
    // when the newest did.
    private fun drain(drop: DropPolicy, write: (T) -> Unit, writeGap: (Gap) -> Unit) {
        if (drop == DropPolicy.OLDEST) writeGapIfAny(writeGap)
        while (pending.isNotEmpty()) {
            write(pending.first())
            pending.removeFirst()
        }
        if (drop == DropPolicy.NEWEST) writeGapIfAny(writeGap)
    }

    private fun writeGapIfAny(writeGap: (Gap) -> Unit) {
        val current = gap ?: return
        writeGap(current)
        gap = null
    }

    private fun hold(item: T, rule: RetryRule) {
        pending.addLast(item)
        while (pending.size > rule.capacity) {
            lose(if (rule.drop == DropPolicy.OLDEST) pending.removeFirst() else pending.removeLast())
        }
    }

    private fun lose(item: T) {
        dropped++
        val at = timestampOf(item)
        gap = gap?.let { it.copy(count = it.count + 1, from = minOf(it.from, at), to = maxOf(it.to, at)) }
            ?: Gap(1, at, at)
    }
}
