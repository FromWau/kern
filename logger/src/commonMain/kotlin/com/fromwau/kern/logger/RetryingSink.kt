package com.fromwau.kern.logger

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Wraps a [LogSink] that has no durability of its own, such as one posting to your own HTTP endpoint, so an entry
 * it refuses is held and delivered later instead of lost. It never throws to the logger.
 *
 * Refused entries are held up to [capacity] and offered again, oldest first, on the first entry logged after
 * [backoff] has passed. When some had to be dropped, the sink first receives a warning entry saying how many and
 * between which times. A sink that already persists and retries, as crash reporting SDKs do, does not need this.
 *
 * ```kotlin
 * val logger = Logger(sinks = listOf(RetryingSink(httpSink, capacity = 500, backoff = 5.seconds)))
 * ```
 *
 * @param drop which end gives way when [capacity] is reached.
 */
public class RetryingSink internal constructor(
    private val sink: LogSink,
    private val capacity: Int,
    private val drop: DropPolicy,
    private val backoff: Duration,
    timeSource: TimeSource,
) : LogSink {
    public constructor(
        sink: LogSink,
        capacity: Int = 1_000,
        drop: DropPolicy = DropPolicy.OLDEST,
        backoff: Duration = 1.seconds,
    ) : this(sink, capacity, drop, backoff, TimeSource.Monotonic)

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(!backoff.isNegative()) { "backoff must not be negative, was $backoff" }
    }

    private class Held(val entry: LogEntry, val config: LoggerConfig)

    // One RetryingSink may be shared by two loggers, each holding only its own lock.
    private val lock = reentrantLock()
    private val retry = RetryBuffer<Held>(timeSource) { it.entry.timestamp }
    private val rule = RetryRule(capacity, drop, backoff)
    private val mutableHealth = MutableStateFlow(BufferHealth())

    // The config the held entries came with, for the gap entry a last attempt may have to send.
    private var lastConfig: LoggerConfig? = null

    /** What the wrapped sink has refused and what was dropped. */
    public val health: StateFlow<BufferHealth> = mutableHealth.asStateFlow()

    /** The wrapped sink's, so an entry it would not take is never held for it either. */
    override val minLevel: LogLevel
        get() = sink.minLevel

    override fun write(entry: LogEntry, config: LoggerConfig) {
        if (entry.level.severity < minLevel.severity) return
        lock.withLock {
            lastConfig = config
            val wasIdle = retry.idle
            // A refused entry is held or dropped by the rule and shows in health, so the failure is not needed here.
            val _ = retry.send(
                Held(entry, config),
                rule,
                write = { sink.write(it.entry, it.config) },
                writeGap = { gap -> sink.write(gapEntry(gap, "this sink"), config) },
            )
            if (!wasIdle || !retry.idle) mutableHealth.value = retry.health
        }
    }

    /**
     * One last attempt at everything held, regardless of the backoff, giving up on what is still refused.
     *
     * @return how many entries were lost and why, or null when nothing was.
     */
    internal fun flushOrAbandon(): Pair<Long, Exception>? = lock.withLock {
        val config = lastConfig ?: return@withLock null
        retry.flushOrAbandon(
            drop,
            write = { sink.write(it.entry, it.config) },
            writeGap = { gap -> sink.write(gapEntry(gap, "this sink"), config) },
        ).also { mutableHealth.value = retry.health }
    }
}

/** The [RetryingSink] behind this sink, through any [atLeast], or null if there is none. */
internal fun LogSink.retrying(): RetryingSink? = when (this) {
    is RetryingSink -> this
    is LevelLimitedSink -> sink.retrying()
    else -> null
}
