package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/** Runs on real threads: each sink's worker, and Dispatchers.Default for the callers. */
class ThreadedTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    /**
     * Installs a sink that runs [call] from inside its own write of the entry "trigger", plus an ordinary sink
     * named "other", logs the trigger, and returns what [call] returned, how long it took and the sink's handle.
     */
    private suspend fun <T> callFromInsideWrite(call: (InstalledSink) -> T): Triple<T, Duration, InstalledSink> {
        val lock = reentrantLock()
        var outcome: Pair<T, Duration>? = null
        lateinit var self: InstalledSink
        val reentrant = Recorder(
            name = "reentrant",
            onWrite = { entry ->
                if (entry.message == "trigger") {
                    val start = TimeSource.Monotonic.markNow()
                    val result = call(self)
                    lock.withLock { outcome = result to start.elapsedNow() }
                }
            },
        )
        self = logger.addOrFail(reentrant)
        val _ = logger.addOrFail(Recorder("other"))

        logger.tag("app").i { "trigger" }
        eventually(10.seconds) { lock.withLock { outcome != null } }
        val (result, took) = lock.withLock { outcome!! }
        return Triple(result, took, self)
    }

    @Test
    fun `flush from inside a sink's write reports that sink at once and waits for the others`() = runTest {
        val (flushed, took, self) = callFromInsideWrite { logger.flush(5.seconds) }

        assertEquals(Ok(Flushed.StillQueued(mapOf(self.name to Pending.TimedOut(1)))), flushed)
        assertTrue(took < 2.seconds, "flush waited $took for the sink it was called from")
    }

    @Test
    fun `close from inside a sink's write reports that sink at once and closes the others`() = runTest {
        val (closed, took, self) = callFromInsideWrite { logger.close(5.seconds) }

        assertEquals(self.closeFailed(SinkError.TimedOut(1)), closed)
        assertTrue(took < 2.seconds, "close waited $took for the sink it was called from")
    }

    @Test
    fun `a sink closing itself from inside its write returns at once`() = runTest {
        val (closed, took, self) = callFromInsideWrite { self -> self.close(5.seconds) }

        assertEquals(self.closeFailed(SinkError.TimedOut(1)), closed)
        assertTrue(took < 2.seconds, "close waited $took")
    }

    @Test
    fun `a sink removing itself from inside its write returns at once and is removed`() = runTest {
        val (_, took, _) = callFromInsideWrite { self -> logger.removeOrFail(self.sink) }

        assertTrue(took < 2.seconds, "configure waited $took")
        assertEquals(listOf("other"), logger.sinks.value.map { it.sink.name })
    }

    @Test
    fun `a sink removing itself from inside its write is closed only after that write returns`() = runTest {
        lateinit var sink: Recorder
        val closesDuringWrite = atomic(-1)
        sink = Recorder(
            name = "self-removing",
            onWrite = {
                logger.removeOrFail(sink)
                closesDuringWrite.value = sink.closes
            },
        )
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { "trigger" }

        eventually(10.seconds) { sink.closes == 1 }
        assertEquals(0, closesDuringWrite.value, "the sink was closed while its write was still running")
    }

    @Test
    fun `flush blocks until a slow sink has written everything queued before it`() {
        val slow = Recorder(onWrite = { val _ = runBlockingOrNull { delay(20.milliseconds) } })
        val _ = logger.addOrFail(slow)

        repeat(5) { logger.tag("app").i { "entry $it" } }

        assertEquals(Ok(Flushed.FullyWritten), logger.flush(5.seconds))
        assertEquals(5, slow.messages.size)
    }

    @Test
    fun `close during a hold replays the held entries before closing`() {
        val sink = Recorder()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()

        logger.tag("boot").i { "held" }

        assertEquals(Ok(Unit), logger.close())
        assertEquals(listOf("held"), sink.messages)
        assertEquals(1, sink.closes)
    }

    @Test
    fun `close waits for the queue to drain before closing the sink`() {
        val order = mutableListOf<String>()
        val lock = reentrantLock()
        val slow = object : LogSink {
            override val name = "slow"

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
                val _ = runBlockingOrNull { delay(20.milliseconds) }
                lock.withLock { order += entry.message }
                return Ok(Unit)
            }

            override fun close(): EmptyResult<SinkError> {
                lock.withLock { order += "closed" }
                return Ok(Unit)
            }
        }
        val _ = logger.addOrFail(slow)

        repeat(3) { logger.tag("app").i { "entry $it" } }

        assertEquals(Ok(Unit), logger.close(5.seconds))
        assertEquals(listOf("entry 0", "entry 1", "entry 2", "closed"), lock.withLock { order.toList() })
    }

    @Test
    fun `close returns within its timeout even when a write starts after the queue drained`() = runTest {
        val firstStarted = Gate()
        val releaseFirst = Gate()
        val releaseHang = Gate()
        val sink = Recorder(
            onWrite = { entry ->
                when (entry.message) {
                    "first" -> {
                        firstStarted.open()
                        releaseFirst.await()
                    }
                    "hang" -> releaseHang.await()
                }
            },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "first" }

        withContext(Dispatchers.Default) {
            firstStarted.await()
            val closing = async { TimeSource.Monotonic.measureTimedValue { installed.close(1.seconds) } }
            delay(100.milliseconds)
            logger.tag("app").i { "hang" }
            releaseFirst.open()
            val (closed, took) = closing.await()
            releaseHang.open()

            assertTrue(took < 3.seconds, "close took $took")
            assertTrue(closed == Ok(Unit) || closed == installed.closeFailed(SinkError.TimedOut(0)), "$closed")
        }
        eventually { sink.closes == 1 }
    }

    @Test
    fun `during a hold a log call does not wait for another call's message to be built`() = runTest {
        val _ = logger.addOrFail(Recorder())
        logger.holdUntilConfigured()
        val building = Gate()
        val release = Gate()

        withContext(Dispatchers.Default) {
            val slow = launch {
                logger.tag("slow").i {
                    building.open()
                    release.await()
                    "slow"
                }
            }
            building.await()
            val took = TimeSource.Monotonic.measureTime { logger.tag("quick").i { "quick" } }
            release.open()
            slow.join()

            assertTrue(took < 1.seconds, "the quick call waited $took for the slow one's message")
        }
    }

    @Test
    fun `a sink removed while a retried write hangs is closed once the write returns`() = runTest {
        val started = Gate()
        val release = Gate()
        val sink = Recorder(
            onFailure = SinkFailure.Retry(backoff = 1.seconds),
            failWith = { _, _ -> refused },
            onWrite = {
                started.open()
                release.await()
            },
        )
        val _ = logger.addOrFail(sink)
        logger.tag("app").i { "stuck" }

        withContext(Dispatchers.Default) {
            started.await()
            logger.removeOrFail(sink)
            release.open()
        }

        eventually { sink.closes == 1 }
    }

    @Test
    fun `flush from inside a sink's write during a hold replays the held entries to that sink too`() = runTest {
        val heldLogged = Gate()
        val flushed = atomic(false)
        val inner = Recorder(
            name = "inner",
            onWrite = { entry ->
                if (entry.message == "trigger") {
                    heldLogged.await()
                    val _ = logger.flush(1.seconds)
                    flushed.value = true
                }
            },
        )
        val _ = logger.addOrFail(inner)
        val _ = logger.addOrFail(Recorder("other"))

        logger.tag("app").i { "trigger" }
        logger.holdUntilConfigured()
        logger.tag("app").i { "held" }
        heldLogged.open()
        eventually { flushed.value }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("trigger", "held"), inner.messages)
    }

    @Test
    fun `no entry reaches a sink after it was removed and closed`() = runTest {
        val late = atomic(0)
        withContext(Dispatchers.Default) {
            val loggers = List(4) {
                launch {
                    while (isActive) logger.tag("app").i { "entry" }
                }
            }
            repeat(300) {
                val closed = atomic(false)
                val sink = object : LogSink {
                    override val name = "churn"

                    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
                        if (closed.value) late.incrementAndGet()
                        return Ok(Unit)
                    }

                    override fun close(): EmptyResult<SinkError> {
                        closed.value = true
                        return Ok(Unit)
                    }
                }
                val _ = logger.addOrFail(sink)
                logger.removeOrFail(sink)
            }
            delay(100.milliseconds)
            loggers.forEach { it.cancel() }
        }

        assertEquals(0, late.value, "entries written after the removed sink was closed")
    }

    @Test
    fun `many threads logging while sinks come and go lose nothing but what is counted as dropped`() = runTest {
        val threads = 8
        val perThread = 2_000
        val stable = Recorder("stable", capacity = 500)
        val installed = logger.addOrFail(stable)

        withContext(Dispatchers.Default) {
            val churn = launch {
                var round = 0
                while (isActive) {
                    val sink = Recorder("churn")
                    val _ = logger.addOrFail(sink)
                    val level = if (round++ % 2 == 0) LogLevel.DEBUG else LogLevel.VERBOSE
                    logger.configureOrFail(stable) { it.copy(level = level) }
                    logger.removeOrFail(sink)
                }
            }
            List(threads) { thread ->
                launch {
                    repeat(perThread) { seq ->
                        logger.tag("t$thread").i("thread" to thread, "seq" to seq) { "entry" }
                    }
                }
            }.joinAll()
            churn.cancel()
        }
        val flushed = logger.awaitFlushed(10.seconds)

        val dropped = installed.health.value.dropped
        val lost = if (dropped == 0L) {
            Ok(Flushed.FullyWritten)
        } else {
            Err(FlushError.Lost(mapOf(installed.name to dropped), emptyMap()))
        }
        assertEquals(lost, flushed)
        val written = stable.entries.filter { it.tag != INTERNAL_TAG }
        assertEquals(threads * perThread.toLong(), written.size + dropped)
        written.groupBy { it.fields["thread"] }.forEach { (thread, entries) ->
            val seqs = entries.map { it.fields["seq"] as Int }
            assertEquals(seqs.sorted(), seqs, "thread $thread's entries arrived out of order")
        }
    }

    @Test
    fun `every entry logged while the sink list changes reaches exactly one of the old and new sinks`() = runTest {
        val threads = 4
        val perThread = 5_000
        val rounds = mutableListOf<Recorder>()
        val first = Recorder("round 0")
        rounds += first
        val _ = logger.addOrFail(first)

        withContext(Dispatchers.Default) {
            val writers = List(threads) { thread ->
                launch {
                    repeat(perThread) { seq ->
                        logger.tag("t$thread").i("thread" to thread, "seq" to seq) { "entry" }
                    }
                }
            }
            val swapper = launch {
                while (isActive) {
                    val next = Recorder("round ${rounds.size}")
                    rounds += next
                    assertEquals(Ok(Unit), logger.configure { LoggerConfig(listOf(ConfiguredSink(next, allLevels))) })
                    yield()
                }
            }
            writers.joinAll()
            swapper.cancel()
        }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(10.seconds))
        eventually(10.seconds) { rounds.dropLast(1).all { it.closes == 1 } }

        assertTrue(rounds.size > 10, "only ${rounds.size} rounds")
        repeat(threads) { thread ->
            val seqs = rounds.flatMap { round ->
                round.entries.filter { it.fields["thread"] == thread }.map { it.fields["seq"] as Int }
            }
            assertEquals(List(perThread) { it }, seqs, "thread $thread's entries, sink by sink")
        }
    }

    @Test
    fun `configure returns while a removed slow sink is still draining`() = runTest {
        val release = Gate()
        val slow = Recorder(name = "slow", onWrite = { release.await() })
        val _ = logger.addOrFail(slow)
        repeat(3) { logger.tag("app").i { "entry $it" } }

        val took = withContext(Dispatchers.Default) {
            TimeSource.Monotonic.measureTime { logger.removeOrFail(slow) }
        }
        assertEquals(0, slow.closes)
        release.open()

        assertTrue(took < 1.seconds, "configure waited $took")
        eventually { slow.closes == 1 }
        assertEquals(List(3) { "entry $it" }, slow.messages)
    }

    @Test
    fun `flush waits for a removed sink still draining`() = runTest {
        val slow = Recorder(name = "slow", onWrite = { val _ = runBlockingOrNull { delay(100.milliseconds) } })
        val _ = logger.addOrFail(slow)
        repeat(3) { logger.tag("app").i { "entry $it" } }
        logger.removeOrFail(slow)

        val flushed = withContext(Dispatchers.Default) { logger.flush(5.seconds) }

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(List(3) { "entry $it" }, slow.messages)
    }

    @Test
    fun `flush running out of time reports a slow sink as still queued and loses nothing`() = runTest {
        val release = Gate()
        val slow = Recorder(name = "slow", onWrite = { release.await() })
        val installed = logger.addOrFail(slow)
        repeat(3) { logger.tag("app").i { "entry $it" } }

        val flushed = withContext(Dispatchers.Default) { logger.flush(100.milliseconds) }
        release.open()

        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.TimedOut(3)))), flushed)
        assertEquals(Ok(Flushed.FullyWritten), withContext(Dispatchers.Default) { logger.flush(5.seconds) })
        assertEquals(List(3) { "entry $it" }, slow.messages)
        assertEquals(0, installed.health.value.dropped)
    }

    @Test
    fun `flush running out of time reports a removed sink still draining`() = runTest {
        val release = Gate()
        val slow = Recorder(name = "slow", onWrite = { release.await() })
        val installed = logger.addOrFail(slow)
        repeat(3) { logger.tag("app").i { "entry $it" } }
        logger.removeOrFail(slow)

        val flushed = withContext(Dispatchers.Default) { logger.flush(100.milliseconds) }
        release.open()

        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.TimedOut(3)))), flushed)
    }

    @Test
    fun `close waits for a removed sink still draining and closes it once`() = runTest {
        val slow = Recorder(name = "slow", onWrite = { val _ = runBlockingOrNull { delay(100.milliseconds) } })
        val _ = logger.addOrFail(slow)
        repeat(3) { logger.tag("app").i { "entry $it" } }
        logger.removeOrFail(slow)

        val closed = withContext(Dispatchers.Default) { logger.close(5.seconds) }

        assertEquals(Ok(Unit), closed)
        assertEquals(List(3) { "entry $it" }, slow.messages)
        eventually { slow.closes == 1 }
        realDelay(100.milliseconds)
        assertEquals(1, slow.closes)
    }

    @Test
    fun `flush moves on past a sink whose retry fails and still waits for a healthy slow one`() = runTest {
        val failing = Recorder(
            name = "failing",
            onFailure = SinkFailure.Retry(backoff = 10.seconds),
            failWith = { _, _ -> refused },
        )
        val slow = Recorder(name = "slow", onWrite = { val _ = runBlockingOrNull { delay(100.milliseconds) } })
        val installedFailing = logger.addOrFail(failing)
        val _ = logger.addOrFail(slow)

        repeat(3) { logger.tag("app").i { "entry $it" } }
        eventually { installedFailing.health.value.failingSince != null }
        val (flushed, took) = withContext(Dispatchers.Default) {
            TimeSource.Monotonic.measureTimedValue { logger.flush(5.seconds) }
        }

        assertEquals(Ok(Flushed.StillQueued(mapOf(installedFailing.name to Pending.GaveUp(3, refused)))), flushed)
        assertEquals(3, slow.messages.size)
        assertEquals(2, failing.attempts, "the flush made the failing sink retry once at once")
        assertTrue(took < 2.seconds, "flush waited $took")
    }

    @Test
    fun `close running out of time drops what a slow sink still has queued`() = runTest {
        val slow = Recorder(name = "slow", onWrite = { val _ = runBlockingOrNull { delay(200.milliseconds) } })
        val installed = logger.addOrFail(slow)
        repeat(5) { logger.tag("app").i { "entry $it" } }

        val closed = withContext(Dispatchers.Default) { installed.close(300.milliseconds) }

        val timedOut = closed.assertError<CloseError>().errors.getValue(installed.name).single() as SinkError.TimedOut
        val pending = timedOut.pending
        assertTrue(pending in 2..4, "$pending pending")
        eventually { installed.queued == 0 }
        assertEquals(5L, slow.messages.size + installed.health.value.dropped)
        assertEquals(pending - 1L, installed.health.value.dropped, "all but the write still running")
    }

    @Test
    fun `close of a sink whose retry keeps failing drops exactly what it reports at its deadline`() = runTest {
        // Each round logs right before closing, so the close races the worker taking the entry.
        repeat(50) { round ->
            val sink = Recorder(
                name = "failing $round",
                onFailure = SinkFailure.Retry(backoff = 10.seconds),
                failWith = { _, _ -> refused },
            )
            val installed = logger.addOrFail(sink)
            logger.tag("app").i { "entry" }

            val (closed, took) = withContext(Dispatchers.Default) {
                TimeSource.Monotonic.measureTimedValue { installed.close(50.milliseconds) }
            }

            assertTrue(took < 1.seconds, "round $round: close took $took")
            assertEquals(installed.closeFailed(SinkError.TimedOut(1)), closed, "round $round")
            assertEquals(1L, installed.health.value.dropped, "round $round")
            assertEquals(0, installed.queued, "round $round")
            logger.removeOrFail(sink)
        }
    }

    @Test
    fun `a Retry write still running when close gives up is pending and dropped once it fails`() = runTest {
        val release = Gate()
        val sink = Recorder(
            name = "hanging",
            onFailure = SinkFailure.Retry(backoff = 10.seconds),
            failWith = { _, _ -> refused },
            onWrite = { release.await() },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "in flight" }
        eventually { sink.attempts == 1 }

        val closed = withContext(Dispatchers.Default) { installed.close(100.milliseconds) }
        release.open()

        assertEquals(installed.closeFailed(SinkError.TimedOut(1)), closed)
        eventually { installed.health.value.dropped == 1L }
        assertEquals(0, installed.queued)
        // The close the write held up runs once it fails, and tries the gap entry for the drop: one more attempt.
        eventually { sink.closes == 1 }
        val attempts = sink.attempts
        realDelay(50.milliseconds)
        assertEquals(attempts, sink.attempts, "the entry close gave up on was retried")
    }

    @Test
    fun `a Drop sink whose write fails while close waits loses that entry alone and close reports it`() = runTest {
        val started = Gate()
        val release = Gate()
        val sink = Recorder(
            name = "dropping",
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            failWith = { _, entry -> if (entry.message == "entry 0") refused else null },
            onWrite = { entry ->
                if (entry.message == "entry 0") {
                    started.open()
                    release.await()
                } else {
                    // Slow enough that the close sees the failure before the worker reaches the rest.
                    val _ = runBlockingOrNull { delay(50.milliseconds) }
                }
            },
        )
        val installed = logger.addOrFail(sink)
        repeat(4) { logger.tag("app").i { "entry $it" } }

        val closed = withContext(Dispatchers.Default) {
            started.await()
            val closing = async { installed.close(5.seconds) }
            delay(100.milliseconds)
            release.open()
            closing.await()
        }

        assertEquals(installed.closeFailed(SinkError.Lost(1)), closed)
        assertEquals(List(3) { "entry ${it + 1}" }, sink.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
        assertEquals(1L, installed.health.value.dropped)
        assertEquals(1, sink.closes)
    }

    @Test
    fun `a sink added back while it still drains keeps its handle and is never called twice at once`() = runTest {
        val started = Gate()
        val release = Gate()
        val sink = OverlapCountingSink(started, release)
        val first = logger.addOrFail(sink)
        repeat(3) { logger.tag("app").i { "entry $it" } }

        val second = withContext(Dispatchers.Default) {
            started.await()
            logger.removeOrFail(sink)
            val second = logger.addOrFail(sink)
            repeat(3) { logger.tag("app").i { "entry ${it + 3}" } }
            delay(100.milliseconds)
            release.open()
            second
        }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(5.seconds))
        realDelay(200.milliseconds)

        assertEquals(0, sink.overlaps, "calls into the sink overlapped")
        assertEquals(List(6) { "entry $it" }, sink.calls)
        assertSame(first, second)
        assertEquals(listOf(first), logger.sinks.value)
    }

    @Test
    fun `a sink removed during a blocked write is Draining until the write returns and then Closed`() = runTest {
        val release = Gate()
        val sink = Recorder(name = "blocked", onWrite = { release.await() })
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "blocked" }

        withContext(Dispatchers.Default) {
            eventually { sink.attempts == 1 }
            logger.removeOrFail(sink)
            assertEquals(SinkState.Draining, installed.state.value)
            assertEquals(0, sink.closes)

            release.open()
            eventually { installed.state.value == SinkState.Closed }
        }

        assertEquals(1, sink.closes)
        assertEquals(listOf("blocked"), sink.messages)
    }

    @Test
    fun `a sink added back while it still drains takes the settings it is paired with now`() = runTest {
        val release = Gate()
        val sink = Recorder(name = "returning", onWrite = { entry -> if (entry.message == "slow") release.await() })
        val first = logger.addOrFail(sink)
        logger.tag("app").i { "slow" }

        val second = withContext(Dispatchers.Default) {
            eventually { sink.attempts == 1 }
            logger.removeOrFail(sink)
            val second = logger.addOrFail(sink, SinkConfig(LogLevel.WARN))
            logger.tag("app").i { "below the new level" }
            logger.tag("app").w { "written" }
            release.open()
            second
        }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(5.seconds))

        assertSame(first, second)
        assertEquals(SinkConfig(LogLevel.WARN), second.config.value)
        assertEquals(listOf("slow", "written"), sink.messages)
    }

    @Test
    fun `re-leveling several sinks in one configure applies to each entry all at once`() = runTest {
        val perThread = 20_000
        // Room for every entry, so none is dropped and each one missing went to neither sink.
        val left = Recorder("left", capacity = 4 * perThread)
        val right = Recorder("right", capacity = 4 * perThread)
        val debug = SinkConfig(LogLevel.DEBUG)
        val info = SinkConfig(LogLevel.INFO)
        val _ = logger.addOrFail(left, debug)
        val _ = logger.addOrFail(right, info)

        withContext(Dispatchers.Default) {
            val swapper = launch {
                var leftTakesDebug = true
                while (isActive) {
                    leftTakesDebug = !leftTakesDebug
                    val levels = if (leftTakesDebug) {
                        listOf(ConfiguredSink(left, debug), ConfiguredSink(right, info))
                    } else {
                        listOf(ConfiguredSink(left, info), ConfiguredSink(right, debug))
                    }
                    assertEquals(Ok(Unit), logger.configure { LoggerConfig(levels) })
                    yield()
                }
            }
            List(4) { thread ->
                launch {
                    repeat(perThread) { seq -> logger.tag("t$thread").d("seq" to seq) { "entry" } }
                }
            }.joinAll()
            swapper.cancel()
        }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(10.seconds))

        val received = (left.entries + right.entries).groupingBy { it.tag to it.fields["seq"] }.eachCount()
        assertEquals(4 * perThread, received.size, "entries that reached neither sink")
        assertEquals(emptyMap(), received.filterValues { it > 1 }, "entries that reached both sinks")
    }

    @Test
    fun `under sustained drops a sink gets at most one gap entry a second and every drop is counted`() = runTest {
        val sink = Recorder(
            name = "overrun",
            capacity = 1,
            onWrite = { val _ = runBlockingOrNull { delay(1.milliseconds) } },
        )
        val installed = logger.addOrFail(sink)

        withContext(Dispatchers.Default) {
            val until = TimeSource.Monotonic.markNow() + 2_500.milliseconds
            List(4) {
                launch {
                    while (until.hasNotPassedNow()) logger.tag("app").i { "entry" }
                }
            }.joinAll()
        }
        val flushed = logger.flush(5.seconds)

        fun gaps() = sink.entries.filter { it.tag == INTERNAL_TAG }
        fun gapCounts() = gaps().sumOf { it.fields["dropped"] as Long }
        val dropped = installed.health.value.dropped
        assertEquals(Err(FlushError.Lost(mapOf(installed.name to dropped), emptyMap())), flushed)
        assertTrue(dropped > 1_000, "only $dropped dropped")
        assertTrue(gaps().size in 1..4, "${gaps().size} gap entries")
        val apart = gaps().zipWithNext { earlier, later -> later.timestamp - earlier.timestamp }
        // The one-second spacing runs on the monotonic clock, while these timestamps come from the wall clock.
        assertTrue(apart.all { it >= 950.milliseconds }, "gap entries $apart apart")
        assertEquals(dropped, gapCounts() + installed.pendingGap)

        assertEquals(installed.closeFailed(SinkError.Lost(dropped)), logger.close())
        assertEquals(dropped, gapCounts())
    }

    @Test
    fun `a full queue under a burst makes flush report what it dropped though the queue drained`() = runTest {
        val release = Gate()
        val sink = Recorder(
            name = "burst",
            capacity = 2,
            onWrite = { entry -> if (entry.message == "first") release.await() },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "first" }
        eventually { sink.attempts == 1 }
        repeat(5) { logger.tag("app").i { "burst $it" } }
        release.open()

        val flushed = withContext(Dispatchers.Default) { logger.flush(5.seconds) }

        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 3), emptyMap())), flushed)
        assertEquals(0, installed.queued)
        assertEquals(listOf("first", "burst 3", "burst 4"), sink.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
        assertEquals(Ok(Flushed.FullyWritten), withContext(Dispatchers.Default) { logger.flush(5.seconds) })
    }

    /** A [BatchRecorder] busy with "first" until [release] opens, so what is logged meanwhile queues up behind it. */
    private suspend fun busyBatches(
        release: Gate,
        onFailure: SinkFailure = SinkFailure.Drop(),
        failWith: (call: Int, batch: List<LogEntry>) -> SinkError? = { _, _ -> null },
    ): Pair<BatchRecorder, InstalledSink> {
        val sink = BatchRecorder(
            onFailure = onFailure,
            failWith = failWith,
            onBatch = { batch -> if (batch.any { it.message == "first" }) release.await() },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "first" }
        eventually { sink.calls == 1 }
        return sink to installed
    }

    @Test
    fun `a batching sink gets what queued during a write in one call and a lone entry at once`() = runTest {
        val release = Gate()
        val (sink, _) = busyBatches(release)
        repeat(5) { logger.tag("app").i { "queued $it" } }
        release.open()
        assertEquals(Ok(Flushed.FullyWritten), withContext(Dispatchers.Default) { logger.flush(5.seconds) })

        logger.tag("app").i { "lone" }
        eventually { sink.calls == 3 }

        val messages = sink.batches.map { batch -> batch.map { it.message } }
        assertEquals(listOf(listOf("first"), List(5) { "queued $it" }, listOf("lone")), messages)
    }

    @Test
    fun `a sink that does not batch is called once per entry`() = runTest {
        val release = Gate()
        val sink = Recorder(onWrite = { if (it.message == "first") release.await() })
        val _ = logger.addOrFail(sink)
        logger.tag("app").i { "first" }
        eventually { sink.attempts == 1 }
        repeat(5) { logger.tag("app").i { "queued $it" } }
        release.open()

        assertEquals(Ok(Flushed.FullyWritten), withContext(Dispatchers.Default) { logger.flush(5.seconds) })
        assertEquals(6, sink.attempts)
    }

    @Test
    fun `a failed batch under Retry is retried whole and each entry is written once`() = runTest {
        val release = Gate()
        val (sink, installed) = busyBatches(
            release,
            onFailure = SinkFailure.Retry(backoff = 10.milliseconds),
            failWith = { call, _ -> if (call == 2) refused else null },
        )
        repeat(5) { logger.tag("app").i { "queued $it" } }
        release.open()
        // A flush waiting while the batch fails would stop there; once it has failed, a flush retries it at once.
        eventually { installed.health.value.failures == 1L }

        assertEquals(Ok(Flushed.FullyWritten), withContext(Dispatchers.Default) { logger.awaitFlushed(5.seconds) })
        val messages = sink.batches.map { batch -> batch.map { it.message } }
        assertEquals(listOf(listOf("first"), List(5) { "queued $it" }), messages)
        assertEquals(3, sink.calls)
        assertEquals(1, installed.health.value.failures)
        assertEquals(0, installed.health.value.dropped)
    }

    @Test
    fun `a failed batch under Drop is dropped whole and counted per entry`() = runTest {
        val release = Gate()
        val (sink, installed) = busyBatches(
            release,
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            failWith = { call, _ -> if (call == 2) refused else null },
        )
        repeat(5) { logger.tag("app").i { "queued $it" } }
        release.open()

        val flushed = withContext(Dispatchers.Default) { logger.flush(5.seconds) }
        logger.tag("app").i { "after" }
        eventually { sink.calls == 3 }

        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 5L), emptyMap())), flushed)
        assertEquals(5, installed.health.value.dropped)
        assertEquals(1, installed.health.value.failures)
        val (gap, after) = sink.batches.last()
        assertEquals(mapOf("dropped" to 5L), gap.fields)
        assertEquals("after", after.message)
    }

    @Test
    fun `batching keeps each thread's entries in the order it logged them`() = runTest {
        val threads = 8
        val perThread = 2_000
        val sink = BatchRecorder(
            capacity = threads * perThread,
            onBatch = { val _ = runBlockingOrNull { delay(1.milliseconds) } },
        )
        val installed = logger.addOrFail(sink)

        withContext(Dispatchers.Default) {
            List(threads) { thread ->
                launch {
                    repeat(perThread) { seq -> logger.tag("t$thread").i("thread" to thread, "seq" to seq) { "entry" } }
                }
            }.joinAll()
        }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(30.seconds))

        val batches = sink.batches
        assertEquals(0, installed.health.value.dropped)
        assertEquals(threads * perThread, batches.sumOf { it.size })
        assertTrue(batches.any { it.size > 1 }, "no batch held more than one entry")
        assertTrue(batches.all { it.size <= 256 }, "a batch held ${batches.maxOf { it.size }} entries")
        batches.flatten().groupBy { it.fields["thread"] }.forEach { (thread, entries) ->
            assertEquals(List(perThread) { it }, entries.map { it.fields["seq"] }, "thread $thread")
        }
    }

    @Test
    fun `a flush never reports a sink whose writes keep failing as fully written wherever it starts`() = runTest {
        // Each case starts the flush at another point of the worker's take, attempt and backoff.
        for (batching in listOf(false, true)) {
            for (point in listOf("before the take", "during the attempt", "during the backoff")) {
                val inAttempt = Gate()
                val release = Gate()
                fun hold(entries: List<LogEntry>) {
                    if (point == "during the attempt" && entries.any { it.message == "entry 0" }) {
                        inAttempt.open()
                        release.await()
                    }
                }
                val name = "failing $batching $point"
                val retry = SinkFailure.Retry(10.milliseconds)
                val sink: LogSink = if (batching) {
                    BatchRecorder(name, retry, failWith = { _, _ -> refused }, onBatch = ::hold)
                } else {
                    Recorder(name, retry, failWith = { _, _ -> refused }, onWrite = { hold(listOf(it)) })
                }
                val installed = logger.addOrFail(sink)
                repeat(3) { logger.tag("app").i { "entry $it" } }
                when (point) {
                    "during the attempt" -> withContext(Dispatchers.Default) { inAttempt.await() }
                    "during the backoff" -> eventually { installed.health.value.failures >= 1 }
                }

                val flushed = withContext(Dispatchers.Default) {
                    val flushing = async { logger.flush(200.milliseconds) }
                    release.open()
                    flushing.await()
                }

                val pending = (flushed.assertSuccess() as? Flushed.StillQueued)?.pending?.get(installed.name)
                assertEquals(3, pending?.count, "$name: $flushed")
                assertEquals(3, installed.queued, name)
                logger.removeOrFail(sink)
            }
        }
    }

    @Test
    fun `a removed sink that gave up and the new sink that took its name are reported as one entry`() = runTest {
        val logger = testLogger(retireTimeout = 10.seconds)
        val old = Recorder(
            name = "file",
            onFailure = SinkFailure.Retry(backoff = 10.seconds),
            failWith = { _, _ -> refused },
        )
        val oldInstalled = logger.addOrFail(old)
        logger.tag("app").i { "for the old sink" }
        eventually { oldInstalled.health.value.failingSince != null }
        val release = Gate()
        val new = Recorder(name = "file", onWrite = { release.await() })
        logger.configure { LoggerConfig(listOf(ConfiguredSink(new, allLevels))) }.assertSuccess()
        repeat(2) { logger.tag("app").i { "for the new sink $it" } }

        val flushed = withContext(Dispatchers.Default) { logger.flush(300.milliseconds) }
        val closed = withContext(Dispatchers.Default) { logger.close(300.milliseconds) }
        val closedAgain = withContext(Dispatchers.Default) { logger.close(300.milliseconds) }
        release.open()
        logger.removeAll()

        assertEquals(Ok(Flushed.StillQueued(mapOf("file" to Pending.GaveUp(3, refused)))), flushed)
        assertEquals(Err(CloseError(mapOf("file" to listOf(SinkError.TimedOut(3))))), closed)
        val stillWriting = listOf(SinkError.TimedOut(1), SinkError.Lost(2))
        assertEquals(Err(CloseError(mapOf("file" to stillWriting))), closedAgain)
    }

    /** A [testLogger] whose default sink, removed when it was made, has finished draining. */
    private suspend fun settledTestLogger(retireTimeout: Duration): Logger {
        val logger = testLogger(retireTimeout = retireTimeout)
        eventually { logger.draining.value.isEmpty() }
        return logger
    }

    /**
     * Installs a sink named "hung" on [logger] whose write blocks until the returned gate opens, logs three entries,
     * removes the sink while its first write blocks, and returns the gate and the sink's handle.
     */
    private suspend fun removeWhileHung(
        logger: Logger,
        sink: (onWrite: (LogEntry) -> Unit) -> Recorder = { onWrite -> Recorder(name = "hung", onWrite = onWrite) },
    ): Pair<Gate, InstalledSink> {
        val started = Gate()
        val release = Gate()
        val installed = logger.addOrFail(
            sink { _ ->
                started.open()
                release.await(10.seconds)
            },
        )
        repeat(3) { logger.tag("app").i { "entry $it" } }
        withContext(Dispatchers.Default) { started.await() }
        logger.removeOrFail(installed.sink)
        return release to installed
    }

    @Test
    fun `a removed sink whose write hangs past the give-up is Abandoned and close reports its entries lost`() =
        runTest {
            val logger = testLogger(retireTimeout = 300.milliseconds)
            val (release, installed) = removeWhileHung(logger)
            try {
                val (closed, took) = withContext(Dispatchers.Default) { measureTimedValue { logger.close(5.seconds) } }
                val closedAgain = withContext(Dispatchers.Default) { logger.close(5.seconds) }

                val lost = Err(CloseError(mapOf("hung" to listOf(SinkError.Lost(3)))))
                assertEquals(lost, closed)
                assertEquals(lost, closedAgain)
                assertTrue(took < 2.seconds, "close waited $took for the hung sink")
                assertEquals(SinkState.Abandoned, installed.state.value)
                assertEquals(3, installed.health.value.dropped)
                assertEquals(installed.closeFailed(SinkError.ClosedAlready, SinkError.Lost(3)), installed.close())
            } finally {
                release.open()
            }
        }

    @Test
    fun `a flush waiting on a removed sink whose write hangs stops waiting when it is abandoned`() = runTest {
        val logger = testLogger(retireTimeout = 300.milliseconds)
        val (release, _) = removeWhileHung(logger)
        try {
            val (flushed, took) = withContext(Dispatchers.Default) { measureTimedValue { logger.flush(5.seconds) } }

            assertEquals(Err(FlushError.Lost(mapOf("hung" to 3L), emptyMap())), flushed)
            assertTrue(took < 2.seconds, "flush waited $took for the hung sink")
        } finally {
            release.open()
        }
    }

    @Test
    fun `an abandoned sink whose write then returns is closed once and counts nothing again`() = runTest {
        val logger = testLogger(retireTimeout = 300.milliseconds)
        val (release, installed) = removeWhileHung(logger)
        val sink = installed.sink as Recorder
        eventually { installed.state.value == SinkState.Abandoned }
        assertEquals(0, sink.closes)

        release.open()
        eventually { sink.closes == 1 }
        realDelay(100.milliseconds)

        assertEquals(1, sink.closes)
        assertEquals(listOf("entry 0"), sink.messages)
        assertEquals(SinkState.Abandoned, installed.state.value)
        assertEquals(SinkHealth(dropped = 3), installed.health.value)
        assertEquals(0, installed.queued)
        assertEquals(Err(CloseError(mapOf("hung" to listOf(SinkError.Lost(3))))), logger.awaitClosed(5.seconds))
    }

    @Test
    fun `an abandoned sink whose write then fails counts nothing again and reports no failure`() = runTest {
        val reports = mutableListOf<String>()
        val logger = testLogger(reports, retireTimeout = 300.milliseconds)
        val (release, installed) = removeWhileHung(logger) { onWrite ->
            Recorder(name = "hung", onWrite = onWrite, failWith = { _, _ -> refused })
        }
        val sink = installed.sink as Recorder
        eventually { installed.state.value == SinkState.Abandoned }

        release.open()
        eventually { sink.closes == 1 }

        assertEquals(SinkHealth(dropped = 3), installed.health.value)
        assertEquals(emptyList(), reports)
        assertEquals(Err(CloseError(mapOf("hung" to listOf(SinkError.Lost(3))))), logger.awaitClosed(5.seconds))
    }

    @Test
    fun `a sink added back after it was abandoned gets a new handle`() = runTest {
        val logger = testLogger(retireTimeout = 300.milliseconds)
        val (release, first) = removeWhileHung(logger)
        try {
            eventually { first.state.value == SinkState.Abandoned }

            val second = logger.addOrFail(first.sink)

            assertTrue(first !== second, "the abandoned handle came back")
            assertEquals(SinkState.Active, second.state.value)
        } finally {
            release.open()
        }
    }

    @Test
    fun `a removed sink whose slow writes all return drains everything however long it takes`() = runTest {
        val logger = testLogger(retireTimeout = 200.milliseconds)
        val slow = Recorder(name = "slow", onWrite = { val _ = runBlockingOrNull { delay(100.milliseconds) } })
        val installed = logger.addOrFail(slow)
        repeat(5) { logger.tag("app").i { "entry $it" } }

        logger.removeOrFail(slow)
        eventually { installed.state.value != SinkState.Draining }

        assertEquals(SinkState.Closed, installed.state.value)
        assertEquals(List(5) { "entry $it" }, slow.messages)
        assertEquals(SinkHealth(), installed.health.value)
    }

    @Test
    fun `a removed sink whose write starts late and hangs is abandoned once that write has hung for the give-up`() =
        runTest {
            val logger = testLogger(retireTimeout = 500.milliseconds)
            val slowStarted = Gate()
            val hungStarted = atomic<TimeSource.Monotonic.ValueTimeMark?>(null)
            val release = Gate()
            val sink = Recorder(
                name = "late",
                onWrite = { entry ->
                    if (entry.message.startsWith("slow")) {
                        slowStarted.open()
                        val _ = runBlockingOrNull { delay(300.milliseconds) }
                    } else {
                        hungStarted.value = TimeSource.Monotonic.markNow()
                        release.await(10.seconds)
                    }
                },
            )
            val installed = logger.addOrFail(sink)
            repeat(2) { logger.tag("app").i { "slow $it" } }
            logger.tag("app").i { "hung" }
            try {
                withContext(Dispatchers.Default) { slowStarted.await() }
                logger.removeOrFail(sink)
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(3.seconds) { while (hungStarted.value == null) delay(5.milliseconds) }
                }
                val hungAt = assertNotNull(hungStarted.value, "the sink was abandoned before its slow writes returned")
                eventually { installed.state.value == SinkState.Abandoned }
                val hungFor = hungAt.elapsedNow()

                // hungAt is taken inside onWrite, a moment after the worker marks the write's start.
                assertTrue(hungFor >= 495.milliseconds, "abandoned after the write hung for only $hungFor")
                assertEquals(List(2) { "slow $it" }, sink.messages)
                assertEquals(Err(CloseError(mapOf("late" to listOf(SinkError.Lost(1))))), logger.awaitClosed())
            } finally {
                release.open()
            }
        }

    @Test
    fun `a removed sink whose retry hangs after a backoff is abandoned once the retry has hung for the give-up`() =
        runTest {
            val logger = testLogger(retireTimeout = 500.milliseconds)
            val release = Gate()
            val attempts = atomic(0)
            val sink = Recorder(
                name = "retrying",
                onFailure = SinkFailure.Retry(backoff = 200.milliseconds),
                failWith = { attempt, _ -> refused.takeIf { attempt == 1 } },
                onWrite = { if (attempts.incrementAndGet() == 2) release.await(10.seconds) },
            )
            val installed = logger.addOrFail(sink)
            logger.tag("app").i { "hung" }
            try {
                eventually { installed.health.value.failures == 1L }
                logger.removeOrFail(sink)
                eventually { installed.state.value == SinkState.Abandoned }

                assertEquals(1, installed.health.value.dropped)
                assertEquals(2, attempts.value)
            } finally {
                release.open()
            }
        }

    @Test
    fun `a sink added back while its abandoned write runs is not called again until that write returns`() =
        runTest {
            val logger = testLogger(retireTimeout = 300.milliseconds)
            val started = Gate()
            val release = Gate()
            val sink = OverlapCountingSink(started, release)
            val first = logger.addOrFail(sink)
            logger.tag("app").i { "entry 0" }
            withContext(Dispatchers.Default) { started.await() }
            logger.removeOrFail(sink)
            eventually { first.state.value == SinkState.Abandoned }

            val second = logger.addOrFail(sink)
            repeat(2) { logger.tag("app").i { "entry ${it + 1}" } }
            realDelay(200.milliseconds)
            val beforeRelease = sink.calls
            release.open()
            val flushed = logger.awaitFlushed(5.seconds)

            assertTrue(first !== second, "the abandoned handle came back")
            assertEquals(listOf("entry 0"), beforeRelease)
            assertEquals(Err(FlushError.Lost(mapOf("returning" to 1L), emptyMap())), flushed)
            assertEquals(listOf("entry 0", "close", "entry 1", "entry 2"), sink.calls)
            assertEquals(0, sink.overlaps, "calls into the sink overlapped")
        }

    /**
     * Adds more sinks than Dispatchers.IO has threads, each blocking in its write until the returned gate opens,
     * then swaps them all for [healthy] and returns the gate and the stuck sinks' handles.
     */
    private suspend fun swapOutManyStuckSinks(logger: Logger, healthy: Recorder): Pair<Gate, List<InstalledSink>> {
        val release = Gate()
        val entered = atomic(0)
        val stuck = List(80) { index ->
            logger.addOrFail(
                Recorder(
                    name = "stuck $index",
                    onWrite = {
                        entered.incrementAndGet()
                        release.await(20.seconds)
                    },
                ),
            )
        }
        logger.tag("app").i { "stuck" }
        eventually(2.seconds) { entered.value == stuck.size }
        logger.replaceWith(healthy)
        return release to stuck
    }

    @Test
    fun `a configured sink writes every entry while more removed sinks than Dispatchers IO has threads hang`() =
        runTest {
            val logger = testLogger(retireTimeout = 300.milliseconds)
            val healthy = Recorder("healthy")
            val (release, _) = swapOutManyStuckSinks(logger, healthy)
            try {
                repeat(10) { logger.tag("app").i { "entry $it" } }
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(2.seconds) { while (healthy.messages.size < 10) delay(5.milliseconds) }
                }

                assertEquals(List(10) { "entry $it" }, healthy.messages)
            } finally {
                release.open()
            }
        }

    @Test
    fun `more removed sinks than Dispatchers IO has threads hanging in a write are each abandoned`() = runTest {
        val logger = testLogger(retireTimeout = 300.milliseconds)
        val (release, stuck) = swapOutManyStuckSinks(logger, Recorder("healthy"))
        try {
            eventually(3.seconds) { logger.draining.value.isEmpty() }

            assertEquals(stuck.size, stuck.count { it.state.value == SinkState.Abandoned })
        } finally {
            release.open()
        }
    }

    @Test
    fun `the app's own Dispatchers IO work runs while more sinks than it has threads hang in a write`() = runTest {
        val logger = testLogger(retireTimeout = 300.milliseconds)
        val (release, _) = swapOutManyStuckSinks(logger, Recorder("healthy"))
        try {
            val ran = withContext(Dispatchers.Default) {
                withTimeoutOrNull(1.seconds) { withContext(Dispatchers.IO) { true } }
            }

            assertEquals(true, ran, "a Dispatchers.IO task did not run within a second")
        } finally {
            release.open()
        }
    }

    @Test
    fun `draining lists removed sinks while a write blocks oldest removal first and drops each once Closed`() =
        runTest {
            val logger = settledTestLogger(retireTimeout = 10.seconds)
            val (releaseFirst, first) = removeWhileHung(logger)
            val (releaseSecond, second) = removeWhileHung(logger) { onWrite -> Recorder("second", onWrite = onWrite) }

            assertEquals(listOf(first, second), logger.draining.value)
            assertEquals(emptyList(), logger.sinks.value)

            releaseFirst.open()
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.draining.first { it == listOf(second) } }
            }
            assertEquals(SinkState.Closed, first.state.value)

            releaseSecond.open()
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.draining.first { it.isEmpty() } }
            }
        }

    @Test
    fun `an abandoned sink is listed in abandoned until its stuck write returns and its close ran`() = runTest {
        val logger = settledTestLogger(retireTimeout = 300.milliseconds)
        val (release, installed) = removeWhileHung(logger)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.abandoned.first { it == listOf(installed) } }
            }
            // Listed as abandoned a moment before it leaves draining.
            eventually { logger.draining.value.isEmpty() }

            assertEquals(SinkState.Abandoned, installed.state.value)
        } finally {
            release.open()
        }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { logger.abandoned.first { it.isEmpty() } }
        }

        assertEquals(1, (installed.sink as Recorder).closes)
        assertEquals(SinkState.Abandoned, installed.state.value)
    }

    @Test
    fun `a removed sink whose write returns in time is never listed in abandoned`() = runTest {
        val logger = settledTestLogger(retireTimeout = 10.seconds)
        val seen = mutableListOf<List<InstalledSink>>()
        val lock = reentrantLock()
        backgroundScope.launch(Dispatchers.Default) { logger.abandoned.collect { lock.withLock { seen += it } } }
        val (release, installed) = removeWhileHung(logger)

        release.open()
        eventually { installed.state.value == SinkState.Closed }
        realDelay(50.milliseconds)

        assertEquals(listOf(emptyList()), lock.withLock { seen.toList() })
    }

    @Test
    fun `several abandoned sinks of one name are each listed in abandoned oldest first`() = runTest {
        val logger = settledTestLogger(retireTimeout = 300.milliseconds)
        val (releaseFirst, first) = removeWhileHung(logger)
        var releaseSecond: Gate? = null
        try {
            // Removed together, the two give-up timers can fire in either order, and the list is in abandonment order.
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.abandoned.first { it == listOf(first) } }
            }
            val (release, second) = removeWhileHung(logger)
            releaseSecond = release
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.abandoned.first { it.size == 2 } }
            }

            assertEquals(listOf(first, second), logger.abandoned.value)
        } finally {
            releaseFirst.open()
            releaseSecond?.open()
        }
    }

    @Test
    fun `a sink added back just as its removed handle closes keeps what that handle lost`() = runTest {
        val logger = settledTestLogger(retireTimeout = 10.seconds)
        val writeGate = Gate()
        val readdArmed = atomic(false)
        lateinit var old: InstalledSink
        val sink = object : LogSink {
            override val name = "returning"
            override val onFailure = SinkFailure.Drop(backoff = Duration.ZERO)
            override val drop = DropPolicy.OLDEST

            // Read while configure holds the logger's lock: lets the removed handle close right then.
            override val capacity: Int
                get() {
                    if (readdArmed.value) {
                        writeGate.open()
                        val _ = runBlockingOrNull {
                            withTimeoutOrNull(5.seconds) { old.state.first { it == SinkState.Closed } }
                        }
                    }
                    return 100
                }

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
                writeGate.await()
                return Err(refused)
            }
        }
        old = logger.addOrFail(sink)
        logger.tag("app").i { "lost" }
        eventually { old.queued == 1 }
        logger.removeOrFail(sink)

        readdArmed.value = true
        val second = withContext(Dispatchers.Default) { logger.addOrFail(sink) }
        readdArmed.value = false

        assertTrue(old !== second, "the closed handle came back")
        assertEquals(SinkState.Closed, old.state.value)
        assertEquals(
            Err(CloseError(mapOf("returning" to listOf(SinkError.Lost(1))))),
            withContext(Dispatchers.Default) { logger.close(5.seconds) },
        )
    }

    @Test
    fun `an abandoned sink leaves draining`() = runTest {
        val logger = settledTestLogger(retireTimeout = 300.milliseconds)
        val (release, installed) = removeWhileHung(logger)
        try {
            assertEquals(listOf(installed), logger.draining.value)

            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { logger.draining.first { it.isEmpty() } }
            }
            assertEquals(SinkState.Abandoned, installed.state.value)
        } finally {
            release.open()
        }
    }

    @Test
    fun `a sink added back while it drains leaves draining`() = runTest {
        val logger = settledTestLogger(retireTimeout = 10.seconds)
        val (release, installed) = removeWhileHung(logger)
        try {
            assertEquals(listOf(installed), logger.draining.value)

            assertSame(installed, logger.addOrFail(installed.sink))

            assertEquals(emptyList(), logger.draining.value)
        } finally {
            release.open()
        }
    }

    /**
     * Records each call into it, write or close, and counts the calls that overlapped one another. Writing
     * "entry 0" opens [started] and blocks until [release] opens.
     */
    private class OverlapCountingSink(private val started: Gate, private val release: Gate) : LogSink {
        override val name = "returning"
        private val inside = atomic(0)
        private val overlapCount = atomic(0)
        private val lock = reentrantLock()
        private val recorded = mutableListOf<String>()

        val overlaps: Int
            get() = overlapCount.value

        /** Every call so far, in order: the message of each write, "close" for each close. */
        val calls: List<String>
            get() = lock.withLock { recorded.toList() }

        override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = call(entry.message) {
            if (entry.message == "entry 0") {
                started.open()
                release.await(10.seconds)
            }
        }

        override fun close(): EmptyResult<SinkError> = call("close") {}

        private fun call(what: String, block: () -> Unit): EmptyResult<SinkError> {
            if (inside.incrementAndGet() > 1) overlapCount.incrementAndGet()
            lock.withLock { recorded += what }
            try {
                block()
            } finally {
                inside.decrementAndGet()
            }
            return Ok(Unit)
        }
    }

    /** Blocks a thread until [open], for at most [limit]. */
    private class Gate {
        private val opened = CompletableDeferred<Unit>()

        fun open() {
            val _ = opened.complete(Unit)
        }

        fun await(limit: Duration = 5.seconds) {
            val _ = runBlockingOrNull { withTimeoutOrNull(limit) { opened.await() } }
        }
    }
}
