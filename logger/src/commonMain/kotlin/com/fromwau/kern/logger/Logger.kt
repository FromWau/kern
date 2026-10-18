@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.getOrElse
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.plusAssign
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/** How many entries [Logger.holdUntilConfigured] holds; past that the oldest give way. */
private const val MAX_HELD = 1024

/**
 * Sends each entry to the [LogSink]s in its [config]. A log call checks the levels, builds the message and queues the
 * entry for each sink that takes it, then returns. It never suspends, never throws and never waits for a sink to
 * write; it may wait briefly for a lock while it queues the entry or during a hold. Each sink writes on its own
 * worker, so a slow disk or a failing network holds up nothing but that sink.
 *
 * ```kotlin
 * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(StderrSink(format = LogFormat.JSON))) }
 *     .onError { error -> System.err.println("no JSON log: $error") }
 * Log.tag("Scanner").i("count" to files.size) { "scan complete" }
 * Log.close()   // on the way out: waits for each sink's queue, then closes that sink
 *     .onError { failed -> System.err.println("some logs were not written: ${failed.errors}") }
 * ```
 *
 * A new logger starts with one [PlatformSink], as [Log] does. Use [Log] unless you need a second, independent
 * logger. Safe to call from any thread.
 */
public class Logger {
    // Test seams, set right after construction. Kept out of the constructor, which a user's mistyped call lists.
    @Volatile
    internal var report: (String) -> Unit = ::reportToStandardError

    @Volatile
    internal var retireTimeout: Duration = 5.seconds

    // Only waits run here; every call into a sink runs on that sink's own dispatcher.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = reentrantLock()
    private val mutableConfig = MutableStateFlow(LoggerConfig(emptyList()))
    private val mutableSinks = MutableStateFlow<List<InstalledSink>>(emptyList())
    private val delivery = AtomicReference(Delivery(emptyList(), emptyList()))

    // Removed sinks whose worker may still be draining, so a config adding one back can take its handle back.
    private val retiring = mutableListOf<InstalledSink>()

    // Abandoned sinks whose call may still be running, so a new handle for the same object waits for it.
    private val stalled = mutableListOf<InstalledSink>()

    // By name, what removed sinks done draining report: their last close's failure and all they lost, which every
    // close reports, and what they lost since the last flush or close, which the next flush reports.
    private val removedErrors = mutableMapOf<String, List<SinkError>>()
    private val removedLostSinceReport = mutableMapOf<String, Long>()
    private val mutableDraining = MutableStateFlow<List<InstalledSink>>(emptyList())
    private val mutableAbandoned = MutableStateFlow<List<InstalledSink>>(emptyList())

    // The names whose failure was reported and that have not written since; failingNames mirrors its size.
    private val failureLock = reentrantLock()
    private val reportedNames = mutableSetOf<String>()
    private val failingNames = AtomicInt(0)

    private val holding = AtomicBoolean(false)
    private val held = ArrayDeque<Held>()
    private var droppedFromThisHold = 0L
    private val mutableHeldDropped = MutableStateFlow(0L)

    /** The sinks the logger sends its entries to, each with the settings in force. Change it with [configure]. */
    public val config: StateFlow<LoggerConfig> = mutableConfig.asStateFlow()

    /** A handle for each sink in [config], in the same order, which is the order each entry reaches them. */
    public val sinks: StateFlow<List<InstalledSink>> = mutableSinks.asStateFlow()

    /**
     * The sinks [configure] removed that are still writing what they had queued, their [InstalledSink.state]
     * [SinkState.Draining], oldest removal first. A sink leaves once it is closed or abandoned, or added back.
     */
    public val draining: StateFlow<List<InstalledSink>> = mutableDraining.asStateFlow()

    /**
     * The removed sinks [SinkState.Abandoned] in a call that has not returned yet, oldest abandonment first. A sink
     * joins as it leaves [draining], and leaves once that call has returned and its close has run; its state stays
     * Abandoned.
     */
    public val abandoned: StateFlow<List<InstalledSink>> = mutableAbandoned.asStateFlow()

    /** How many entries [holdUntilConfigured] dropped because more than 1024 were held at once. */
    public val heldDropped: StateFlow<Long> = mutableHeldDropped.asStateFlow()

    init {
        val _ = configure { LoggerConfig() }
    }

    /**
     * Changes the config in one step: the sinks, and the [SinkConfig] each is configured with.
     *
     * ```kotlin
     * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(StderrSink(), SinkConfig(level = LogLevel.DEBUG))) }
     *     .onError { error -> Log.tag("App").w(error) { "no debug output" } }
     * ```
     *
     * Every entry logged before the change goes to the old list's sinks by their old levels, and every entry after it
     * to the new list's sinks by their new ones.
     *
     * A sink in both lists, the same object, keeps its [InstalledSink], with its queue and health, and takes the
     * settings its [ConfiguredSink] has now. An added sink starts taking entries. A removed sink takes no further
     * entries, writes what it had queued and is then closed on its own worker; this call does not wait for that.
     * Added back before that close, it takes its [InstalledSink] back, queue and all. A removed sink that keeps
     * failing under [SinkFailure.Retry] gives up after 5 seconds and counts what it still had as dropped. One whose
     * single write runs for 5 seconds without returning is [SinkState.Abandoned]: what it still had, that write
     * included, counts as lost, and the logger waits for it no more. [draining] lists the removed sinks not yet done.
     *
     * @param transform makes the new config from the current one. It runs once, under the logger's lock, so two calls
     *   from different threads never undo each other's change. If it throws, nothing changes and the exception
     *   reaches you.
     * @return [ConfigError.NameTaken] when two sinks in the new list share a name, the same sink listed twice
     *   included, or [ConfigError.Invalid] when a new sink's capacity is below one, its backoff is negative, or
     *   reading one of its properties threw. Nothing changes then. A name may be reused by a new sink when the change
     *   removes the old one.
     */
    public fun configure(transform: (LoggerConfig) -> LoggerConfig): Result<Unit, ConfigError> = lock.withLock {
        val next = transform(mutableConfig.value)
        val current = mutableSinks.value
        pruneRetiring()
        pruneStalled()
        val planned = next.sinks.map { (sink, config) ->
            current.firstOrNull { it.sink === sink }?.let { Kept(it) } ?: run {
                val settings = settingsOf(sink, config).getOrElse { return Err(it) }
                retiring.firstOrNull { it.sink === sink }?.let { Reclaim(it, settings) } ?: settings
            }
        }
        val names = mutableSetOf<String>()
        planned.forEach { if (!names.add(it.name)) return Err(ConfigError.NameTaken(it.name)) }

        val installed = planned.map { plan ->
            when (plan) {
                is Kept -> plan.installed
                // A handle closed meanwhile stays listed, so its pending retirementEnded keeps what it lost.
                is Reclaim -> plan.take(this, scope).also { if (it === plan.installed) retiring.remove(it) }
                is Settings -> plan.install(this, stalled.lastOrNull { it.sink === plan.sink }?.callsReturned)
                    .also { it.start(scope) }
            }
        }
        installed.forEachIndexed { index, sink -> sink.applyConfig(next.sinks[index].config) }
        val removed = current.filter { old -> installed.none { it === old } }
        val previous = delivery.load()
        delivery.store(Delivery(installed, next.sinks.map { it.config }))
        mutableSinks.value = installed
        mutableConfig.value = next
        retiring += removed
        previous.retire(removed.map { it.retire(retireTimeout) })
        publishDraining()
        Ok(Unit)
    }

    /**
     * Holds every entry from now on, instead of sending it, until [flush], [awaitFlushed], [close] or [awaitClosed],
     * so an app that logs before it has read its own config lets that config decide what the early entries were
     * worth.
     *
     * ```kotlin
     * Log.holdUntilConfigured()
     * Log.tag("Startup").d { "looking for a config file" }       // held
     * val config = readConfig()
     * Log.configure { current ->
     *     current.copy(sinks = current.sinks.map { it.copy(config = it.config.copy(level = config.logLevel)) })
     * }.onError { error -> Log.tag("Startup").w(error) { "keeping the default level" } }
     * Log.flush()                                                // replayed if DEBUG is on
     *     .onError { error -> System.err.println("startup logs were lost: $error") }
     * ```
     *
     * A held entry builds its message at once, since no level has decided against it yet. The replay sends each
     * entry to every sink configured when the hold ends whose level takes it at that moment, with the time it was
     * logged. At most 1024 entries are held; past that the oldest give way, counted in [heldDropped], and the replay
     * starts with a warning saying how many. Nothing ends a hold by itself, so end it on every path out of your
     * startup.
     */
    public fun holdUntilConfigured() {
        lock.withLock { holding.store(true) }
    }

    /**
     * Ends a hold, if any, then waits up to [timeout] for every sink to write what was queued before this call, a sink
     * removed by [configure] and still writing what it had queued included. A sink waiting out a [SinkFailure.Retry]
     * backoff tries again at once; a write that fails under Retry while flush waits ends the wait for that sink, and
     * its entries stay queued for the next retry. A write that fails under [SinkFailure.Drop] loses its entry, which
     * the flush reports as lost, and flush goes on waiting for the rest.
     *
     * Called from inside a sink's write it cannot wait for that sink, which is reported as pending at once. On an
     * interrupted thread it waits for nothing and reports what each sink still has queued, keeping the interrupt.
     *
     * ```kotlin
     * when (val flushed = Log.flush()) {
     *     Ok(Flushed.FullyWritten) -> Unit
     *     is Ok -> println("still queued, nothing lost: $flushed")
     *     is Err -> println("entries were lost: ${flushed.error}")
     * }
     * ```
     *
     * @return [Flushed.FullyWritten] once every sink wrote everything queued before the call, or
     *   [Flushed.StillQueued] with a [Pending] for each sink name that still holds entries: [Pending.GaveUp] with
     *   the failure of a Retry write, or [Pending.TimedOut] when the time ran out. An error only for a loss:
     *   [FlushError.Lost] with how many entries each name lost since the last flush or close, which this reports
     *   once, and the sinks still holding entries. A sink removed by [configure] adds into the entry of its name. On
     *   JS and Wasm, where a thread cannot block, [FlushError.CannotWait] at once; use [awaitFlushed] there.
     */
    public fun flush(timeout: Duration = 5.seconds): Result<Flushed, FlushError> {
        endHold()
        val waited = waitedOn()
        val sinks = waited.draining
        val writer = currentWriter
        val undrained = runBlockingOrNull { undrained(sinks, writer, timeout, retryFailureEndsWait = true) }
            ?: if (threadsCanWait) waiting(sinks, writer) else return Err(FlushError.CannotWait)
        return flushResult(waited.draining, undrained)
    }

    /**
     * [flush] without blocking a thread, on every platform: ends a hold, if any, and suspends until every sink has
     * written what was queued before this call, for up to [timeout]. The timeout runs in real time, even under
     * `runTest`'s virtual clock.
     *
     * @return what [flush] returns: [Flushed.FullyWritten], [Flushed.StillQueued], or [FlushError.Lost].
     */
    public suspend fun awaitFlushed(timeout: Duration = 5.seconds): Result<Flushed, FlushError> {
        endHold()
        val waited = waitedOn()
        val writer = currentWriter
        // Off the caller's dispatcher, so the timeout runs in real time even under a test's virtual clock.
        val undrained = withContext(Dispatchers.Default) {
            undrained(waited.draining, writer, timeout, retryFailureEndsWait = true)
        }
        return flushResult(waited.draining, undrained)
    }

    /**
     * Ends a hold, if any, waits up to [timeout] for every sink's queue, a sink removed by [configure] and still
     * draining included, then closes each sink. A write that fails under [SinkFailure.Retry] is tried again after
     * the sink's backoff until it is written or the time runs out; then the entries still queued are dropped,
     * counted in [SinkHealth.dropped], before the sink is closed. On an interrupted thread it waits for nothing, so
     * the same happens at once, and the interrupt stays set. Every sink stays configured and takes the next entry,
     * so call this where your app exits, not to stop logging. On JS and Wasm it cannot wait, so whatever is still
     * queued is dropped: call [awaitClosed] there.
     *
     * @return `Ok` only when every sink wrote everything queued before the call, never lost an entry since it was
     *   added, and closed cleanly, so this result alone can set an exit status. Otherwise [CloseError] with each
     *   other sink's errors by name. First [SinkError.TimedOut] when the time ran out, or else the error the sink's
     *   own close returned. Then [SinkError.Lost] with every entry lost under that name since it was first added,
     *   including losses a flush already reported and entries an earlier close dropped. A sink removed by
     *   [configure] adds into the entry of its name. A flush after this reports only what is lost from then on.
     */
    public fun close(timeout: Duration = 5.seconds): EmptyResult<CloseError> {
        endHold()
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val waited = waitedOn(closing = true)
        val sinks = waited.draining
        val writer = currentWriter
        val undrained = runBlockingOrNull { undrained(sinks, writer, timeout, retryFailureEndsWait = false) }
            ?: waiting(sinks, writer)
        val closed = sinks.map { sink ->
            sink.name to when (sink) {
                writer -> sink.closeErrors(SinkError.TimedOut(sink.queued))
                else -> sink.closeAfter(drained = sink !in undrained, deadline, once = sink in waited.retiring)
            }
        }
        return closeResult(closed + waited.removedErrors)
    }

    /**
     * [close] without blocking a thread, on every platform: ends a hold, if any, suspends until every sink's queue
     * has drained, for up to [timeout] of real time, then closes each sink.
     *
     * @return `Ok` only when every sink wrote everything, never lost an entry since it was added and closed
     *   cleanly; otherwise [CloseError], as [close] returns it.
     */
    public suspend fun awaitClosed(timeout: Duration = 5.seconds): EmptyResult<CloseError> {
        endHold()
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val waited = waitedOn(closing = true)
        val sinks = waited.draining
        val writer = currentWriter
        // Closing calls into each sink, which may block.
        val closed = withContext(sinkDispatcher()) {
            val undrained = undrained(sinks, writer, timeout, retryFailureEndsWait = false)
            sinks.map { sink ->
                sink.name to when (sink) {
                    writer -> sink.closeErrors(SinkError.TimedOut(sink.queued))
                    else -> sink.awaitCloseAfter(drained = sink !in undrained, deadline, once = sink in waited.retiring)
                }
            }
        }
        return closeResult(closed + waited.removedErrors)
    }

    /** Names the source of the entries you are about to log: `Log.tag("Scanner").i { "done" }`. */
    public fun tag(tag: String): TaggedLogger = TaggedLogger(tag, this)

    /**
     * Logs one entry. Prefer [tag], which reads better at a call site.
     *
     * @param fields structured values; see [TaggedLogger] for how each type is written.
     * @param at the time the entry happened, for one forwarded from elsewhere; null for now.
     * @param message built only when at least one configured sink takes [level], or at once during a hold. If it
     *   throws anything, an `Error` included, the entry is logged with `<message threw ...>` in its place.
     */
    public fun log(
        tag: String,
        level: LogLevel,
        throwable: Throwable? = null,
        error: ResultError? = null,
        fields: Map<String, Any?> = emptyMap(),
        at: Instant? = null,
        message: () -> String,
    ) {
        if (level == LogLevel.OFF) return

        fun entry() = LogEntry(at ?: Clock.System.now(), tag, level, build(message), fields, throwable, error)

        // Built before taking the lock a hold needs, so a slow or locking message holds up no other log call.
        val built = if (holding.load()) entry().also { if (hold(it)) return } else null
        if (level.severity < delivery.load().lowest) return
        dispatch(built ?: entry(), currentWriter)
    }

    /** Reports [message] for the sink [name] unless a failure of that name was reported since it last wrote. */
    internal fun reportFirstFailure(name: String, message: String) {
        val first = failureLock.withLock {
            reportedNames.add(name).also { failingNames.store(reportedNames.size) }
        }
        if (first) reportFailure(message)
    }

    /** Lets the next failure of the sink [name] be reported again. */
    internal fun wroteSuccessfully(name: String) {
        if (failingNames.load() == 0) return
        failureLock.withLock {
            if (reportedNames.remove(name)) failingNames.store(reportedNames.size)
        }
    }

    /** Lets go of a removed sink that was closed or abandoned. */
    internal fun retirementEnded() {
        lock.withLock { pruneRetiring() }
    }

    /** Lets go of an abandoned sink whose stuck call has returned and whose close has run. */
    internal fun callsReturned() {
        lock.withLock { pruneStalled() }
    }

    // A failure while reporting a failure is swallowed: there is nowhere left to report it.
    internal fun reportFailure(message: String) {
        try {
            report("$INTERNAL_TAG: $message")
        } catch (_: Exception) {
        }
    }

    /** Queues [entry] for every sink that takes it except [writer], the sink whose write logged it. */
    private fun dispatch(entry: LogEntry, writer: InstalledSink?) {
        while (true) {
            val current = delivery.load()
            current.enter()
            // A config change in between retired the list this call joined, and may already have ended its intake.
            if (delivery.load() !== current) {
                current.exit()
                continue
            }
            try {
                current.send(entry, writer)
            } finally {
                current.exit()
            }
            return
        }
    }

    /** Holds [entry], unless the hold ended meanwhile. */
    private fun hold(entry: LogEntry): Boolean = lock.withLock {
        if (!holding.load()) return false
        held.addLast(Held(entry, currentWriter))
        if (held.size > MAX_HELD) {
            held.removeFirst()
            droppedFromThisHold++
            mutableHeldDropped.value++
        }
        true
    }

    private fun endHold() {
        lock.withLock {
            if (!holding.load()) return
            val dropped = droppedFromThisHold
            if (dropped > 0) {
                dispatch(
                    internalEntry(
                        LogLevel.WARN,
                        "dropped the $dropped oldest held entries: more than $MAX_HELD were held",
                    ),
                    writer = null,
                )
            }
            held.forEach { dispatch(it.entry, it.writer) }
            held.clear()
            droppedFromThisHold = 0
            holding.store(false)
        }
    }

    /**
     * Waits for every sink of [sinks] but [writer], all within [timeout]; returns why each sink whose queue did not
     * drain did not, [writer] among them when it is one of [sinks].
     */
    private suspend fun undrained(
        sinks: List<InstalledSink>,
        writer: InstalledSink?,
        timeout: Duration,
        retryFailureEndsWait: Boolean,
    ): Map<InstalledSink, Pending> {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val waits = sinks.filter { it !== writer }.map { it.beginWait() }
        return buildMap {
            if (writer != null && writer in sinks) put(writer, Pending.TimedOut(writer.queued))
            waits.forEach { wait ->
                wait.sink.awaitDrained(wait, -deadline.elapsedNow(), retryFailureEndsWait)?.let { put(wait.sink, it) }
            }
        }
    }

    /** What a call that could not wait leaves undrained: [writer], and every sink with entries still queued. */
    private fun waiting(sinks: List<InstalledSink>, writer: InstalledSink?): Map<InstalledSink, Pending> =
        sinks.filter { it === writer || it.queued > 0 }.associateWith { Pending.TimedOut(it.queued) }

    /**
     * The configured sinks, the removed ones still draining, and what the removed ones done draining lost; [closing]
     * also resets what the next flush reports of the latter.
     */
    private fun waitedOn(closing: Boolean = false): Waited = lock.withLock {
        pruneRetiring()
        if (closing) removedLostSinceReport.clear()
        Waited(mutableSinks.value, retiring.toList(), removedErrors.toList())
    }

    /** Lets go of each removed sink done draining, keeping what it reports under its name. */
    private fun pruneRetiring() {
        val pruned = retiring.removeAll { sink ->
            sink.isDone.also { done ->
                if (done) keepErrors(sink)
                if (sink.state.value == SinkState.Abandoned && !sink.callsReturned.isCompleted) stalled += sink
            }
        }
        if (pruned) {
            // Listed as abandoned before it leaves draining, so a reader of both never misses it.
            mutableAbandoned.value = stalled.toList()
            publishDraining()
        }
    }

    private fun pruneStalled() {
        if (stalled.removeAll { it.callsReturned.isCompleted }) mutableAbandoned.value = stalled.toList()
    }

    private fun publishDraining() {
        mutableDraining.value = retiring.filter { it.state.value == SinkState.Draining }
    }

    private fun keepErrors(sink: InstalledSink) {
        val errors = listOfNotNull(sink.retiredWith, SinkError.Lost(sink.lostInAll))
        removedErrors[sink.name] = foldCloseErrors(removedErrors[sink.name].orEmpty() + errors)
        removedLostSinceReport.add(sink.name, sink.takeLostSinceReport())
    }

    private fun flushResult(
        sinks: List<InstalledSink>,
        undrained: Map<InstalledSink, Pending>,
    ): Result<Flushed, FlushError> {
        // Under the lock that moves a finished sink's loss into removedLostSinceReport, so each loss is taken once.
        val lost = lock.withLock {
            val taken = sinks.map { it.name to it.takeLostSinceReport() } + removedLostSinceReport.toList()
            removedLostSinceReport.clear()
            sumByName(taken).filterValues { it > 0 }
        }
        val stillQueued = pendingByName(sinks.mapNotNull { sink -> undrained[sink]?.let { sink.name to it } })
        return when {
            lost.isNotEmpty() -> Err(FlushError.Lost(lost, stillQueued))
            stillQueued.isEmpty() -> Ok(Flushed.FullyWritten)
            else -> Ok(Flushed.StillQueued(stillQueued))
        }
    }

    private class Waited(
        configured: List<InstalledSink>,
        val retiring: List<InstalledSink>,
        val removedErrors: List<Pair<String, List<SinkError>>>,
    ) {
        /** What a flush or close waits for. */
        val draining: List<InstalledSink> = configured + retiring
    }

    private class Held(val entry: LogEntry, val writer: InstalledSink?)

    /** What a config change does with one sink of the new list. */
    private sealed interface Plan {
        val name: String
    }

    /** A sink in both lists, which keeps its handle. */
    private class Kept(val installed: InstalledSink) : Plan {
        override val name: String
            get() = installed.name
    }

    /**
     * A removed sink added back while its worker may still drain it, which takes its handle back, so no two workers
     * ever call the sink at once; a new handle from [settings] once that worker is done.
     */
    private class Reclaim(val installed: InstalledSink, val settings: Settings) : Plan {
        override val name: String
            get() = installed.name

        fun take(owner: Logger, scope: CoroutineScope): InstalledSink =
            if (installed.reclaim()) {
                installed
            } else {
                settings.install(owner, installed.callsReturned).also { it.start(scope) }
            }
    }

    /** A new sink's properties, read once and checked. */
    private class Settings(
        val sink: LogSink,
        override val name: String,
        val config: SinkConfig,
        val onFailure: SinkFailure,
        val capacity: Int,
        val drop: DropPolicy,
    ) : Plan {
        fun install(owner: Logger, predecessor: Job?) =
            InstalledSink(sink, owner, name, config, onFailure, capacity, drop, predecessor)
    }

    private fun settingsOf(sink: LogSink, config: SinkConfig): Result<Settings, ConfigError> {
        val name = try {
            sink.name
        } catch (e: Throwable) {
            return Err(ConfigError.Invalid("<unnamed>", "reading its name threw $e"))
        }
        val settings = try {
            Settings(sink, name, config, sink.onFailure, sink.capacity, sink.drop)
        } catch (e: Throwable) {
            return Err(ConfigError.Invalid(name, "reading its settings threw $e"))
        }
        if (settings.capacity < 1) {
            return Err(ConfigError.Invalid(name, "capacity must be at least 1, was ${settings.capacity}"))
        }
        if (settings.onFailure.backoff.isNegative()) {
            return Err(ConfigError.Invalid(name, "backoff must not be negative, was ${settings.onFailure.backoff}"))
        }
        return Ok(settings)
    }
}

/**
 * One configured list of sinks with the settings each has, and how many log calls are still sending to
 * it. Once a config change retires it, the last of those calls ends the intake of the sinks the change removed, so
 * no entry reaches a closed queue.
 */
private class Delivery(
    val sinks: List<InstalledSink>,
    configs: List<SinkConfig>,
) {
    private val levels = IntArray(sinks.size) { configs[it].level.severity }

    /** The least severe level any sink takes, so a log call below it returns before building its message. */
    val lowest: Int = levels.minOrNull() ?: LogLevel.OFF.severity

    private val senders = AtomicInt(0)
    private val removed = AtomicReference<List<InstalledSink.Retirement>?>(null)
    private val ended = AtomicBoolean(false)

    /** Queues [entry] for every sink whose level takes it except [writer], the sink whose write logged it. */
    fun send(entry: LogEntry, writer: InstalledSink?) {
        val severity = entry.level.severity
        sinks.forEachIndexed { index, sink -> if (sink !== writer && severity >= levels[index]) sink.offer(entry) }
    }

    fun enter() {
        senders += 1
    }

    fun exit() {
        if (senders.decrementAndFetch() == 0) endIntakeIfRetired()
    }

    fun retire(removed: List<InstalledSink.Retirement>) {
        this.removed.store(removed)
        if (senders.load() == 0) endIntakeIfRetired()
    }

    private fun endIntakeIfRetired() {
        val removed = removed.load() ?: return
        if (ended.compareAndSet(expectedValue = false, newValue = true)) removed.forEach { it.endIntake() }
    }
}

private fun MutableMap<String, Long>.add(name: String, count: Long) {
    if (count > 0) this[name] = (this[name] ?: 0L) + count
}

/** The process-wide logger, starting with one [PlatformSink]. Change its sinks with [Logger.configure]. */
public val Log: Logger = Logger()

private val SinkFailure.backoff: Duration
    get() = when (this) {
        is SinkFailure.Drop -> backoff
        is SinkFailure.Retry -> backoff
    }

private fun build(message: () -> String): String = try {
    message()
} catch (e: Throwable) {
    "<message threw ${e::class.simpleName}>"
}
