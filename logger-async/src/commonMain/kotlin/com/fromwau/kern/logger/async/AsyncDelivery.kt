package com.fromwau.kern.logger.async

import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.LogDelivery
import com.fromwau.kern.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Writes each entry on a background writer instead of the thread that logged, so a log call never waits for the
 * disk or a slow sink: what a Compose app's main thread wants.
 *
 * ```kotlin
 * val logger = Logger(sinks = listOf(crashSink), delivery = AsyncDelivery())
 * logger.close()   // waits for the queue to drain, then closes the file
 * ```
 *
 * Entries wait in a queue of [capacity]. When the writer falls that far behind, [drop] decides which entry gives
 * way, and [health] counts it; a log call never blocks. [Logger.close] waits for the queue to drain. On the JVM the
 * queue is also drained, for up to [flushOnExit], when the process exits, and on Android before an uncaught
 * exception ends it. Native targets have no such hook, so call [Logger.close] before exiting there.
 *
 * Each entry is written with the configuration in force when the writer reaches it. A failure set to
 * `FileFailure.Throw` or `SinkFailure.Throw` cannot reach the call that logged from here, so it is counted in
 * [health] instead.
 *
 * @param capacity how many entries may wait for the writer.
 * @param drop which waiting entry gives way when [capacity] is reached.
 * @param flushOnExit how long the exit hook waits for the queue to drain.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AsyncDelivery(
    capacity: Int = 10_000,
    drop: DropPolicy = DropPolicy.OLDEST,
    flushOnExit: Duration = 2.seconds,
) : LogDelivery {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val submitted = AtomicLong(0)

    // Counted on the logging threads, which may be hundreds at once, so a plain atomic rather than a flow: the
    // writer publishes it in health.
    private val dropped = AtomicLong(0)

    // Moved by the writer alone. Flush waits until written and dropped together reach what was submitted, rather
    // than for a marker a full queue could drop. Long.MAX_VALUE once the writer has stopped.
    private val written = MutableStateFlow(0L)

    private val mutableHealth = MutableStateFlow(AsyncHealth())

    /** What the writer dropped or failed to write. */
    public val health: StateFlow<AsyncHealth> = mutableHealth.asStateFlow()

    // DROP_LATEST would discard the newest without calling onUndeliveredElement, so a full queue refuses the
    // send instead, and submit counts that as the drop.
    private val queue = Channel<() -> Unit>(
        capacity = capacity,
        onBufferOverflow = if (drop == DropPolicy.OLDEST) BufferOverflow.DROP_OLDEST else BufferOverflow.SUSPEND,
        onUndeliveredElement = { dropOne() },
    )

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                for (write in queue) {
                    run(write)
                    written.value += 1
                    publishDrops()
                }
            } finally {
                // Only an Error gets here. Nothing will write again, so a flush must not wait for it, and
                // what is still queued is dropped.
                written.value = Long.MAX_VALUE
                queue.cancel()
                publishDrops()
            }
        }
        onProcessExit { flushWithin(flushOnExit) }
    }

    private fun dropOne() {
        dropped.incrementAndFetch()
        // A stopped writer publishes nothing more, so the logging thread does it.
        if (written.value == Long.MAX_VALUE) publishDrops()
    }

    private fun publishDrops() {
        val now = dropped.load()
        if (now != mutableHealth.value.dropped) mutableHealth.update { it.copy(dropped = now) }
    }

    override fun submit(write: () -> Unit) {
        submitted.incrementAndFetch()
        // Refused when the queue is full under DropPolicy.NEWEST, or closed because an Error stopped the writer.
        if (queue.trySend(write).isFailure) dropOne()
    }

    override fun flush() {
        runBlocking { awaitSettled(submitted.load()) }
    }

    private fun flushWithin(timeout: Duration) {
        runBlocking { withTimeoutOrNull(timeout) { awaitSettled(submitted.load()) } }
    }

    // Rechecks now and then as well: a drop counted on a logging thread can land after the writer's last write,
    // when nothing moves written any more.
    private suspend fun awaitSettled(target: Long) {
        fun settled(writes: Long) = writes == Long.MAX_VALUE || writes + dropped.load() >= target
        while (withTimeoutOrNull(10.milliseconds) { written.first(::settled) } == null) {
            if (settled(written.value)) break
        }
        publishDrops()
    }

    // A CancellationException is counted like any other: it is a sink's own, and rethrowing it here would stop the
    // writer for good.
    private fun run(write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            mutableHealth.update { it.copy(failed = it.failed + 1, lastError = e) }
        }
    }
}

/**
 * @param dropped how many entries gave way because the queue was full.
 * @param failed how many writes threw, which only a `FileFailure.Throw` or `SinkFailure.Throw` makes them do.
 * @param lastError the most recent of those failures, or null if there was none.
 */
public data class AsyncHealth(
    val dropped: Long = 0,
    val failed: Long = 0,
    val lastError: Throwable? = null,
)

/** Runs [flush] when the process is about to end, where the platform offers a hook for it. */
internal expect fun onProcessExit(flush: () -> Unit)
