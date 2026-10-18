@file:OptIn(ExperimentalAtomicApi::class, InternalKernApi::class)

package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The least time between two entries saying how many a sink dropped. */
private val GAP_INTERVAL: Duration = 1.seconds

/**
 * The most entries a [BatchSink] gets in one call: enough that a backlog costs a file one append per few hundred
 * lines, few enough that a failed append retries or drops a bounded amount.
 */
internal const val MAX_BATCH = 256

/** No overflow timestamp recorded. */
private const val NO_TIME = Long.MIN_VALUE

/** One call writing [batch] into a sink, told apart from the next even when a retry writes the same batch. */
private class Write(val batch: List<LogEntry>) {
    val started: TimeMark = TimeSource.Monotonic.markNow()
}

/** What [InstalledSink]'s running write is swapped for once the give-up abandons it. */
private val ABANDONED = Write(emptyList())

/**
 * A [LogSink] in a [Logger]'s config, as [Logger.sinks] lists it: the settings it has there, its health, and
 * the handle to close it. It lives as long as its sink stays in the config, and until a removed sink has written
 * what it had queued.
 */
public class InstalledSink internal constructor(
    /** The sink as it was configured. */
    public val sink: LogSink,
    private val owner: Logger,
    /** The sink's name, read once when it was configured. */
    public val name: String,
    config: SinkConfig,
    private val onFailure: SinkFailure,
    capacity: Int,
    drop: DropPolicy,
    private val predecessor: Job?,
) {
    private val mutableConfig = MutableStateFlow(config)
    private val mutableHealth = MutableStateFlow(SinkHealth())

    // DROP_LATEST would discard the newest without calling onUndeliveredElement, so a full queue refuses the send
    // instead, and offer counts that as the drop.
    private val queue = Channel<LogEntry>(
        capacity = capacity,
        onBufferOverflow = if (drop == DropPolicy.OLDEST) BufferOverflow.DROP_OLDEST else BufferOverflow.SUSPEND,
        onUndeliveredElement = ::undelivered,
    )

    // Every entry accepted is settled once, written or dropped, so flush waits on these instead of a queue size.
    // progress is bumped only while someone waits, so settling an entry takes no lock otherwise.
    private val accepted = AtomicLong(0)
    private val settled = AtomicLong(0)
    private val waiters = AtomicInt(0)
    private val progress = MutableStateFlow(0L)

    // Guards the gap, which a close dropping entries and the worker both move.
    private val lock = reentrantLock()
    private var gap: Gap? = null

    // What a full queue dropped, counted without a lock by the log calls and folded into gap and health later.
    private val overflowed = AtomicLong(0)
    private val overflowFrom = AtomicLong(NO_TIME)
    private val overflowTo = AtomicLong(NO_TIME)

    private val batches = sink as? BatchSink

    private val closeRequested = AtomicBoolean(false)

    // Held around every call into the sink, so close never runs during a write.
    private val callLock = reentrantLock()
    private lateinit var scope: CoroutineScope

    // The write running now, or ABANDONED once the give-up let go of one.
    private val writing = AtomicReference<Write?>(null)

    // Signalled as a removed sink starts a write, so its stall watch need not poll.
    private val writeStarts = Channel<Unit>(Channel.CONFLATED)

    /** Completes once this handle makes no further call into [sink], so a new handle for it may start. */
    internal val callsReturned: CompletableJob = Job()

    // A flush or close that starts waiting bumps wakes, which cuts a Retry backoff short.
    private val wakes = AtomicLong(0)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Guarded by lock: a close giving up on the queue drops the entry waiting for a retry, and one whose write was
    // running then once it fails, counting each here.
    private var abandoning = 0
    private var abandons = 0L
    private var droppedByClose = 0L
    private var retryWaiting: List<LogEntry>? = null
    private var retryClaimed = false

    // Set once the sink is removed from its logger: past it, a sink still failing under Retry gives up.
    private val retireDeadline = AtomicReference<TimeMark?>(null)

    // Guards the removal's end and every state change: a config adding the sink back takes the handle until it is
    // Closed or Abandoned.
    private val lifeLock = reentrantLock()
    private var retirements = 0L
    private var intakeEnded = false
    private var stallWatch: Job? = null

    // What the sink lost by itself: since the last flush or close, which reports it, and since it was added.
    private val lostSinceReport = AtomicLong(0)
    private val lostSinceAdded = AtomicLong(0)

    // Touched by the worker alone.
    private var droppingUntil: TimeMark? = null
    private var gaveUpOn = -1L

    // Touched only while holding callLock.
    private var lastGapWritten: TimeMark? = null
    private var closedSinceWrite = false
    private var lastClose: EmptyResult<SinkError> = Ok(Unit)

    private val mutableState = MutableStateFlow(SinkState.Active)

    /**
     * Whether the sink is in its logger's config, removed and still writing what it had queued, closed for good, or
     * abandoned because, once removed, one write ran 5 seconds without returning. To wait until a sink you removed is
     * done:
     *
     * ```kotlin
     * old.state.first { it == SinkState.Closed || it == SinkState.Abandoned }
     * ```
     *
     * To delete what the sink wrote, wait for Closed alone: an Abandoned sink may still be inside its write.
     */
    public val state: StateFlow<SinkState> = mutableState.asStateFlow()

    /** The settings the sink has in its logger's config, or had when it was removed. */
    public val config: StateFlow<SinkConfig> = mutableConfig.asStateFlow()

    /**
     * What the sink has dropped and failed on. Quiet while every write succeeds. Entries a full queue drops show here
     * once the sink's worker takes its next entry, a flush or close starts, or you read this property.
     */
    public val health: StateFlow<SinkHealth>
        get() {
            foldOverflow()
            return readOnlyHealth
        }

    private val readOnlyHealth = mutableHealth.asStateFlow()

    /**
     * How many entries are waiting for the sink right now, those it is writing included: up to the sink's capacity
     * plus one, or plus 256 for a `FileSink`, which writes up to that many at once.
     */
    public val queued: Int
        get() = (accepted.load() - settled.load()).toInt()

    /** How many dropped entries the next gap entry will report. */
    internal val pendingGap: Long
        get() {
            foldOverflow()
            return lock.withLock { gap?.count ?: 0 }
        }

    /**
     * Waits up to [timeout] for the entries already queued to be written, then closes the sink, releasing what it
     * holds open. A sink waiting out a [SinkFailure.Retry] backoff tries again at once. The sink stays configured
     * and its [state] does not change: the next entry is written as usual, reopening what it needs.
     *
     * A write that fails under [SinkFailure.Retry] while close waits is tried again after the sink's backoff, until
     * it is written or the time runs out. Then the entries still queued are dropped, counted in [SinkHealth.dropped],
     * and the sink is closed anyway, once a write still running returns. Under [SinkFailure.Drop] a failed write
     * loses its entry as usual and close goes on waiting for the rest.
     * Called from inside this sink's own write it cannot wait for itself: it reports [SinkError.TimedOut] at once and
     * leaves the sink open. On JS and Wasm, where a thread cannot wait, entries still queued are dropped the same
     * way; use [Logger.awaitClosed] there.
     *
     * @return `Ok` only when the sink wrote everything queued before the call, never lost an entry since it was
     *   added, and closed cleanly. Otherwise [CloseError] with this sink's errors under its name, as [Logger.close]
     *   reports them: [SinkError.TimedOut] with every entry not yet written, a write still running included, the
     *   error the sink's own close returned or threw, or [SinkError.ClosedAlready] once [state] is [SinkState.Closed]
     *   or [SinkState.Abandoned]. Then [SinkError.Lost] with how many entries it dropped since it was added, those
     *   an earlier close dropped included.
     */
    public fun close(timeout: Duration = 5.seconds): EmptyResult<CloseError> {
        val errors = when {
            isDone -> closeErrors(SinkError.ClosedAlready)
            currentWriter === this -> closeErrors(SinkError.TimedOut(queued))
            else -> {
                val deadline = TimeSource.Monotonic.markNow() + timeout
                val wait = beginWait()
                val drained = isSettled(wait.target) ||
                    runBlockingOrNull { awaitDrained(wait, timeout, false) == null } == true
                closeAfter(drained, deadline, once = retireDeadline.load() != null)
            }
        }
        return closeResult(listOf(name to errors))
    }

    override fun toString(): String = "InstalledSink(name=$name, state=${state.value}, queued=$queued)"

    internal fun start(scope: CoroutineScope) {
        this.scope = scope
        val _ = scope.launch(sinkDispatcher()) {
            try {
                // The sink's handle before this one was abandoned in a call that may still be running.
                predecessor?.join()
                closeIfRequested()
                while (true) {
                    if (isAbandoned) {
                        callingSink { val _ = closeSinkOnce() }
                        break
                    }
                    val entry = queue.tryReceive().getOrNull()
                    if (entry != null) {
                        deliver(batchFrom(entry))
                        closeIfRequested()
                    } else if (isDrainedAndRetired()) {
                        val closed = callLock.withLock { closeSinkLocked(once = true) }
                        // Added back while that close ran: the next entry reopens what the sink needs.
                        if (finishRetirement(closed)) break
                    } else {
                        select {
                            queue.onReceive { deliver(batchFrom(it)) }
                            wake.onReceive {}
                        }
                        closeIfRequested()
                    }
                }
            } finally {
                closeIfRequested()
                if (callsReturned.complete() && isAbandoned) owner.callsReturned()
            }
        }
    }

    /**
     * Starts a removed sink's drain deadline. Its worker closes the sink once the returned retirement's intake has
     * ended and it drained, unless [reclaim] takes it back first. One write running for [timeout] abandons it.
     */
    internal fun retire(timeout: Duration): Retirement {
        val retirement = lifeLock.withLock {
            val deadline = TimeSource.Monotonic.markNow() + timeout
            retireDeadline.store(deadline)
            intakeEnded = false
            mutableState.value = SinkState.Draining
            val generation = ++retirements
            stallWatch?.cancel()
            stallWatch = scope.launch { watchWrites(timeout, generation) }
            Retirement(generation)
        }
        wakeUp()
        return retirement
    }

    /**
     * Takes a removed sink back into a config, keeping its queue, order and health; false once its worker has closed
     * it for good, and a new handle must take its place.
     */
    internal fun reclaim(): Boolean = lifeLock.withLock {
        if (isDone) return false
        retireDeadline.store(null)
        intakeEnded = false
        stallWatch?.cancel()
        stallWatch = null
        mutableState.value = SinkState.Active
        true
    }

    /** Pairs the sink with [config], as the logger's config now says. */
    internal fun applyConfig(config: SinkConfig) {
        mutableConfig.value = config
    }

    /** What the close that ended the sink's removal failed with, once it is [SinkState.Closed]. */
    @Volatile
    internal var retiredWith: SinkError? = null
        private set

    /** Whether the sink was removed for good and its worker has finished or was let go of. */
    internal val isDone: Boolean
        get() = mutableState.value.let { it == SinkState.Closed || it == SinkState.Abandoned }

    private val isAbandoned: Boolean
        get() = writing.load() === ABANDONED

    /** Queues [entry] for the worker, or drops one by its [DropPolicy] when the queue is full. Never blocks. */
    internal fun offer(entry: LogEntry) {
        accepted += 1
        if (queue.trySend(entry).isSuccess) return
        overflow(entry)
    }

    /** How many entries the sink lost since the last flush or close, but for those a close reported as pending. */
    internal fun takeLostSinceReport(): Long = lostSinceReport.exchange(0)

    /** How many entries the sink lost since it was added, those a close dropped included. */
    internal val lostInAll: Long
        get() = lostSinceAdded.load() + lock.withLock { droppedByClose }

    /**
     * What a close reports for this sink: [failure], then [SinkError.Lost] with every entry lost since it was added,
     * those dropped by closes up to [closeDrops] included. A flush after this reports only what is lost from now on.
     */
    internal fun closeErrors(
        failure: SinkError?,
        closeDrops: Long = lock.withLock { droppedByClose },
    ): List<SinkError> {
        // Reset before reading: a loss racing this call then reaches the next flush as well, rather than no flush.
        lostSinceReport.store(0)
        val lost = lostSinceAdded.load() + closeDrops
        return listOfNotNull(failure, lost.takeIf { it > 0 }?.let(SinkError::Lost))
    }

    /** Counts entries [sink] lost by itself, such as the lines of a deleted file, in health and the next gap entry. */
    internal fun droppedBySink(lost: Gap) {
        countLost(lost.count)
        lock.withLock { gap = lost.plus(gap) }
        mutableHealth.update { it.copy(dropped = it.dropped + lost.count) }
    }

    /** What a flush or close waits for, taken as it starts; it also cuts a Retry backoff short. */
    internal fun beginWait(): Wait {
        foldOverflow()
        val wait = Wait(this, accepted.load(), mutableHealth.value.failures)
        wakeUp()
        return wait
    }

    /**
     * Waits up to [timeout] for everything [wait] covers to be written or dropped; null once it is, or why not. With
     * [retryFailureEndsWait], a write failing under [SinkFailure.Retry] ends the wait with [Pending.GaveUp]. A
     * failure under [SinkFailure.Drop] holds up nothing behind it, so the wait goes on.
     */
    internal suspend fun awaitDrained(wait: Wait, timeout: Duration, retryFailureEndsWait: Boolean): Pending? {
        if (isSettled(wait.target)) return null
        val failureEndsWait = retryFailureEndsWait && onFailure is SinkFailure.Retry
        if (timeout.isPositive()) {
            val _ = whileWaiting {
                withTimeoutOrNull(timeout) {
                    combine(progress, mutableHealth) { _, health ->
                        isSettled(wait.target) || (failureEndsWait && health.failures > wait.failures)
                    }.first { it }
                }
            }
        }
        if (isSettled(wait.target)) return null
        foldOverflow()
        val health = mutableHealth.value
        val failure = health.lastError?.takeIf { failureEndsWait && health.failures > wait.failures }
        return if (failure != null) Pending.GaveUp(queued, failure) else Pending.TimedOut(queued)
    }

    /**
     * Ends a close that has waited: drops what is left unless [drained], then closes the sink before [deadline]; with
     * [once], not again if nothing was written since its last close. Returns what the close reports for the sink.
     */
    internal fun closeAfter(drained: Boolean, deadline: TimeMark, once: Boolean): List<SinkError> = finishClose(
        drained,
        once,
        settleOrGiveUp = { target -> val _ = runBlockingOrNull { awaitSettled(target, deadline) } },
        lockCall = {
            val predecessorReturned = predecessor == null ||
                predecessor.isCompleted ||
                runBlockingOrNull { withTimeoutOrNull(-deadline.elapsedNow()) { predecessor.join() } } != null
            predecessorReturned && (callLock.tryLock() || runBlockingOrNull { awaitCallLock(deadline) } == true)
        },
    )

    /** [closeAfter] without blocking a thread while a write runs. */
    internal suspend fun awaitCloseAfter(
        drained: Boolean,
        deadline: TimeMark,
        once: Boolean,
    ): List<SinkError> = finishClose(
        drained,
        once,
        settleOrGiveUp = { target -> awaitSettled(target, deadline) },
        lockCall = {
            val predecessorReturned = predecessor == null ||
                withTimeoutOrNull(-deadline.elapsedNow()) { predecessor.join() } != null
            predecessorReturned && (callLock.tryLock() || awaitCallLock(deadline))
        },
    )

    private fun isSettled(target: Long): Boolean = settled.load() >= target

    private suspend fun awaitSettled(target: Long, deadline: TimeMark) {
        val _ = whileWaiting { withTimeoutOrNull(-deadline.elapsedNow()) { progress.first { isSettled(target) } } }
    }

    /** Runs [block] counted as a waiter, so every entry settled meanwhile bumps [progress]. */
    private inline fun <T> whileWaiting(block: () -> T): T {
        waiters += 1
        try {
            return block()
        } finally {
            waiters -= 1
        }
    }

    private inline fun finishClose(
        drained: Boolean,
        once: Boolean,
        settleOrGiveUp: (target: Long) -> Unit,
        lockCall: () -> Boolean,
    ): List<SinkError> {
        // What this close drops is reported as its TimedOut; what earlier closes dropped, as lost.
        val earlierCloseDrops = lock.withLock { droppedByClose }
        val pending = if (drained) 0 else abandonQueued(settleOrGiveUp)
        val closed = when {
            isAbandoned -> null
            lockCall() -> try {
                closeRequested.store(false)
                // Its worker closes an abandoned sink once the write it was let go in returns.
                if (isAbandoned) null else closeSinkLocked(once)
            } finally {
                callLock.unlock()
            }
            else -> {
                requestClose()
                null
            }
        }
        val failure = when {
            pending > 0 -> SinkError.TimedOut(pending)
            isAbandoned -> null
            closed == null -> SinkError.TimedOut(0)
            else -> closed.errorOrNull()
        }
        return closeErrors(failure, earlierCloseDrops)
    }

    /**
     * Drops every entry still queued, and the one the worker is retrying or whose write fails before [settleOrGiveUp]
     * returns. Returns how many entries it dropped, plus any write still running then, which is left to land or be
     * retried.
     */
    private inline fun abandonQueued(settleOrGiveUp: (target: Long) -> Unit): Int {
        val target = accepted.load()
        val before = lock.withLock {
            abandoning++
            abandons++
            val before = droppedByClose
            retryWaiting?.takeUnless { retryClaimed }?.let { batch ->
                retryClaimed = true
                loseAll(batch, byClose = true)
            }
            before
        }
        wakeUp()
        while (true) {
            val entry = queue.tryReceive().getOrNull() ?: break
            loseAll(listOf(entry), byClose = true)
        }
        try {
            if (!isSettled(target)) settleOrGiveUp(target)
        } finally {
            lock.withLock { abandoning-- }
        }
        return lock.withLock { droppedByClose - before + (target - settled.load()).coerceAtLeast(0) }.toInt()
    }

    /**
     * Waits for a retry, unless a close is giving up on the queue or gave up while this write ran: then the entries
     * that just failed are its drop, dropped and settled here.
     */
    private fun beginRetryWait(batch: List<LogEntry>, abandonsBefore: Long): Boolean = lock.withLock {
        if (closeGaveUpSince(abandonsBefore)) {
            loseAll(batch, byClose = true)
            true
        } else {
            retryWaiting = batch
            false
        }
    }

    /** Guarded by [lock]: whether a close is giving up on the queue, or gave up since [abandonsBefore]. */
    private fun closeGaveUpSince(abandonsBefore: Long): Boolean = abandoning > 0 || abandons != abandonsBefore

    /**
     * Ends a retry wait: null when a close claimed the entries meanwhile, and has dropped and settled them; otherwise
     * the close count the retry starts from, taken as the entries leave a close's reach.
     */
    private fun endRetryWait(): Long? = lock.withLock {
        retryWaiting = null
        val claimed = retryClaimed
        retryClaimed = false
        if (claimed) null else abandons
    }

    private fun wakeUp() {
        wakes += 1
        val _ = wake.trySend(Unit)
    }

    /** [first], and for a [BatchSink] whatever else is queued behind it, up to [MAX_BATCH] entries. */
    private fun batchFrom(first: LogEntry): List<LogEntry> {
        if (batches == null) return listOf(first)
        val batch = ArrayList<LogEntry>()
        batch += first
        while (batch.size < MAX_BATCH) batch += queue.tryReceive().getOrNull() ?: break
        return batch
    }

    private suspend fun deliver(batch: List<LogEntry>) {
        foldOverflow()
        var abandonsBefore = lock.withLock { abandons }
        while (true) {
            if (droppingUntil?.hasNotPassedNow() == true) return loseAll(batch)
            if (hasGivenUp()) return loseAll(batch)

            val seen = wakes.load()
            val write = Write(batch)
            if (!beginWrite(write)) return
            val error = attempt(batch)
            if (!endWrite(write)) return
            if (error == null) {
                if (mutableHealth.value.failingSince != null) mutableHealth.update { it.copy(failingSince = null) }
                owner.wroteSuccessfully(name)
                return settle(batch.size)
            }

            failed(error)
            when (onFailure) {
                is SinkFailure.Retry -> {
                    if (retireDeadline.load()?.hasPassedNow() == true) return giveUp(batch)
                    if (beginRetryWait(batch, abandonsBefore)) return
                    awaitRetry(onFailure.backoff, seen)
                    abandonsBefore = endRetryWait() ?: return
                }
                is SinkFailure.Drop -> {
                    droppingUntil = TimeSource.Monotonic.markNow() + onFailure.backoff
                    return loseAll(batch, byClose = lock.withLock { closeGaveUpSince(abandonsBefore) })
                }
            }
        }
    }

    /** Waits out [backoff], or less once a flush or close has started waiting since [seen] or the retire deadline. */
    private suspend fun awaitRetry(backoff: Duration, seen: Long) {
        // delay(ZERO) returns without suspending, which would spin past a cancelled worker.
        if (!backoff.isPositive()) return yield()
        val limit = retireDeadline.load()?.let { minOf(backoff, -it.elapsedNow()) } ?: backoff
        val _ = withTimeoutOrNull(limit) {
            while (wakes.load() == seen) wake.receive()
        }
    }

    /** A removed sink still failing past its deadline: [batch] and everything queued behind it are dropped. */
    private fun giveUp(batch: List<LogEntry>) {
        gaveUpOn = lifeLock.withLock { retirements }
        loseAll(batch)
    }

    /** Marks [write] as running, so the stall watch can abandon it; false once the sink was abandoned. */
    private fun beginWrite(write: Write): Boolean {
        if (!writing.compareAndSet(null, write)) return false
        if (retireDeadline.load() != null) {
            val _ = writeStarts.trySend(Unit)
        }
        return true
    }

    /** False when the stall watch abandoned [write] while it ran, and has counted its entries as lost. */
    private fun endWrite(write: Write): Boolean = writing.compareAndSet(write, null)

    /** Abandons the removed sink once one write has run for [timeout] without returning. */
    private suspend fun watchWrites(timeout: Duration, generation: Long) {
        while (true) {
            val write = writing.load()
            when {
                write == null -> writeStarts.receive()
                write === ABANDONED -> return
                else -> {
                    val left = timeout - write.started.elapsedNow()
                    if (left.isPositive()) delay(left) else if (abandonIfRunning(write, generation)) return
                }
            }
        }
    }

    /**
     * Lets go of a removed sink whose [write] is still running: the entries in it and those still queued are lost,
     * and the sink is [SinkState.Abandoned]. False when [write] has returned meanwhile.
     */
    private fun abandonIfRunning(write: Write, generation: Long): Boolean {
        val abandoned = lifeLock.withLock {
            if (generation != retirements || retireDeadline.load() == null || isDone) return true
            if (!writing.compareAndSet(write, ABANDONED)) return false
            loseAll(write.batch)
            // Each entry still queued reaches undelivered, which counts it lost; a later offer is refused and counted.
            queue.cancel()
            mutableState.value = SinkState.Abandoned
            stallWatch = null
            true
        }
        if (abandoned) owner.retirementEnded()
        return true
    }

    private fun hasGivenUp(): Boolean =
        gaveUpOn >= 0 && lifeLock.withLock { gaveUpOn == retirements && retireDeadline.load() != null }

    private fun isDrainedAndRetired(): Boolean = lifeLock.withLock {
        intakeEnded && retireDeadline.load() != null && queued == 0
    }

    /** Ends the worker unless the sink was added back meanwhile; [closed] is how its last close went. */
    private fun finishRetirement(closed: EmptyResult<SinkError>): Boolean {
        val finished = lifeLock.withLock {
            isDrainedAndRetired().also { finished ->
                if (finished) {
                    retiredWith = closed.errorOrNull()
                    mutableState.value = SinkState.Closed
                    stallWatch?.cancel()
                    stallWatch = null
                }
            }
        }
        if (finished) owner.retirementEnded()
        return finished
    }

    /**
     * Writes the gap left by dropped entries, if one is due, then [batch]: a [BatchSink] in one call with the gap
     * first, any other sink one entry at a time. Returns why that failed, or null.
     */
    private fun attempt(batch: List<LogEntry>): SinkError? = callingSink {
        val due = lastGapWritten?.let { it.elapsedNow() >= GAP_INTERVAL } ?: true
        val pendingGap = if (due) takeGap() else null
        val gapError: SinkError?
        val error: SinkError?
        if (batches != null) {
            error = writeBatch(batches, if (pendingGap == null) batch else listOf(gapEntry(pendingGap)) + batch)
            gapError = error
        } else {
            gapError = pendingGap?.let { writeOne(gapEntry(it)) }
            error = gapError ?: writeOne(batch.single())
        }
        if (pendingGap != null) {
            if (gapError == null) {
                lastGapWritten = TimeSource.Monotonic.markNow()
            } else {
                lock.withLock { gap = pendingGap.plus(gap) }
            }
        }
        error
    }

    /** Runs [block] holding [callLock], with this sink as the thread's current writer. */
    private inline fun <T> callingSink(block: () -> T): T {
        val previous = currentWriter
        callLock.lock()
        currentWriter = this
        try {
            return block()
        } finally {
            currentWriter = previous
            callLock.unlock()
        }
    }

    private fun writeOne(entry: LogEntry): SinkError? = try {
        closedSinceWrite = false
        sink.write(entry, mutableConfig.value).errorOrNull()
    } catch (e: Throwable) {
        // A CancellationException included: write does not suspend, so it is the sink's own.
        SinkError.Dropped(Thrown(e))
    }

    private fun writeBatch(sink: BatchSink, entries: List<LogEntry>): SinkError? = try {
        closedSinceWrite = false
        sink.writeBatch(entries, mutableConfig.value).errorOrNull()
    } catch (e: Throwable) {
        SinkError.Dropped(Thrown(e))
    }

    // Called by the channel for the oldest entry a full queue drops.
    private fun undelivered(entry: LogEntry) {
        overflow(entry)
    }

    /** Counts [entry], dropped by a full queue, with no lock: the next gap entry and health pick it up. */
    private fun overflow(entry: LogEntry) {
        val at = entry.timestamp.toEpochNanos()
        val _ = overflowFrom.compareAndSet(NO_TIME, at)
        overflowTo.store(at)
        overflowed += 1
        countLost(1)
        settle(1)
    }

    /** Moves what a full queue dropped into the pending gap and [SinkHealth.dropped]. */
    private fun foldOverflow() {
        if (overflowed.load() == 0L) return
        val count = overflowed.exchange(0)
        if (count == 0L) return
        // A drop racing this fold may lend its time to one fold and its count to the next.
        val from = overflowFrom.exchange(NO_TIME)
        val to = overflowTo.exchange(NO_TIME)
        val first = from.takeIf { it != NO_TIME } ?: to
        val last = to.takeIf { it != NO_TIME } ?: from
        val span = if (first == NO_TIME) {
            Clock.System.now().let { Gap(count, it, it) }
        } else {
            Gap(count, minOf(first, last).toInstant(), maxOf(first, last).toInstant())
        }
        lock.withLock { gap = span.plus(gap) }
        mutableHealth.update { it.copy(dropped = it.dropped + count) }
    }

    /** Takes the pending gap, what a full queue dropped included, leaving none. */
    private fun takeGap(): Gap? {
        foldOverflow()
        return lock.withLock { gap.also { gap = null } }
    }

    private fun failed(error: SinkError) {
        mutableHealth.update {
            it.copy(
                failures = it.failures + 1,
                failingSince = it.failingSince ?: Clock.System.now(),
                lastError = error,
            )
        }
        owner.reportFirstFailure(name, "sink \"$name\" failed: $error")
    }

    /**
     * Counts [batch] as dropped and settled; [byClose] when a close gave up on it, which reports it as pending, and
     * every later close as lost.
     */
    private fun loseAll(batch: List<LogEntry>, byClose: Boolean = false) {
        if (!byClose) countLost(batch.size.toLong())
        lock.withLock {
            if (byClose) droppedByClose += batch.size
            batch.forEach { gap = gap.plus(it) }
        }
        mutableHealth.update { it.copy(dropped = it.dropped + batch.size) }
        settle(batch.size)
    }

    private fun countLost(count: Long) {
        lostSinceReport += count
        lostSinceAdded += count
    }

    private fun settle(count: Int) {
        settled += count.toLong()
        if (waiters.load() > 0) progress.value = settled.load()
    }

    private fun closeIfRequested() {
        if (!closeRequested.load()) return
        callLock.withLock { closeIfRequestedLocked() }
    }

    private fun closeIfRequestedLocked() {
        if (closeRequested.exchange(false) && !isAbandoned) {
            val _ = closeSinkLocked()
        }
    }

    /** Leaves the close to whoever next holds [callLock] after a write: the worker, or this call if it is free now. */
    private fun requestClose() {
        closeRequested.store(true)
        // Inside this sink's own write the reentrant lock would let us in mid-write; the worker closes after it.
        if (currentWriter === this) return
        // The worker may have finished its write and checked for a request before the store above.
        if (predecessor?.isCompleted != false && callLock.tryLock()) {
            try {
                closeIfRequestedLocked()
            } finally {
                callLock.unlock()
            }
        }
    }

    // atomicfu's lock has no timed tryLock; this runs on the calling thread, which then owns the lock.
    private suspend fun awaitCallLock(deadline: TimeMark): Boolean {
        while (!callLock.tryLock()) {
            if (deadline.hasPassedNow() || isAbandoned) return false
            delay(1.milliseconds)
        }
        return true
    }

    /**
     * Writes a pending gap, then closes the sink; with [once], only if something was written since its last close.
     * Entries the close itself lost get their gap written after it, and the sink is closed again. A failed close,
     * or a gap it could not write, is a failure of the sink like a failed write.
     */
    private fun closeSinkLocked(once: Boolean = false): EmptyResult<SinkError> {
        if (once && closedSinceWrite) return lastClose
        val closed = callingSink {
            val gapFailure = writeGapLocked()
            val closeFailure = closeSinkOnce()
            foldOverflow()
            val lostWhileClosing = gapFailure == null && lock.withLock { gap != null }
            val lateGapFailure = if (lostWhileClosing) writeGapLocked() else null
            val recloseFailure = if (lostWhileClosing && lateGapFailure == null) closeSinkOnce() else null
            val failure = closeFailure ?: recloseFailure ?: gapFailure ?: lateGapFailure
            if (failure == null) Ok(Unit) else Err(failure)
        }
        closed.errorOrNull()?.let(::failed)
        closedSinceWrite = true
        lastClose = closed
        return closed
    }

    private fun closeSinkOnce(): SinkError? = try {
        sink.close().errorOrNull()
    } catch (e: Throwable) {
        SinkError.Dropped(Thrown(e))
    }

    /** Writes the pending gap, if any; returns why that failed, leaving the gap pending, or null. */
    private fun writeGapLocked(): SinkError? {
        val pendingGap = takeGap() ?: return null
        val failure = writeOne(gapEntry(pendingGap))
        if (failure == null) {
            lastGapWritten = TimeSource.Monotonic.markNow()
        } else {
            lock.withLock { gap = pendingGap.plus(gap) }
        }
        return failure
    }

    /** What a flush or close waits for on one sink: [target] settled, unless a write fails past [failures]. */
    internal class Wait(val sink: InstalledSink, val target: Long, val failures: Long)

    /** One removal of this sink from a config, whose intake ends once the last log call sending to it has. */
    internal inner class Retirement(private val generation: Long) {
        /** Takes no further entries, unless the sink was added back or removed again since. */
        fun endIntake() {
            val ended = lifeLock.withLock {
                (generation == retirements && retireDeadline.load() != null).also { if (it) intakeEnded = true }
            }
            if (ended) wakeUp()
        }
    }
}

private const val NANOS_PER_SECOND = 1_000_000_000L

// Fits a Long until the year 2262.
private fun Instant.toEpochNanos(): Long = epochSeconds * NANOS_PER_SECOND + nanosecondsOfSecond

private fun Long.toInstant(): Instant =
    Instant.fromEpochSeconds(floorDiv(NANOS_PER_SECOND), mod(NANOS_PER_SECOND))
