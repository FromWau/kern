package com.fromwau.kern.logger.test

import com.fromwau.kern.logger.BatchSink
import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.FlushError
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.InternalKernApi
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.logger.Thrown
import com.fromwau.kern.logger.Timestamp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.plusAssign
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.measureTime

@OptIn(ExperimentalAtomicApi::class)
class RecordingSinkTest {
    private val logger = emptyLogger()
    private val refused: SinkError = SinkError.Dropped(Thrown(IllegalStateException("refused")))
    private val entry = LogEntry(Instant.fromEpochSeconds(0), "app", LogLevel.INFO, "hello")

    @AfterTest
    fun cleanup() {
        val _ = logger.close()
    }

    @Test
    fun `it takes its name and settings from the sink it wraps`() {
        val delegate = LogSink(
            "remote",
            onFailure = SinkFailure.Retry(backoff = 2.seconds),
            capacity = 5,
            drop = DropPolicy.NEWEST,
        ) { _, _ -> Ok(Unit) }

        val recording = RecordingSink(delegate)

        assertEquals("remote", recording.name)
        assertEquals(SinkFailure.Retry(backoff = 2.seconds), recording.onFailure)
        assertEquals(5, recording.capacity)
        assertEquals(DropPolicy.NEWEST, recording.drop)
        assertSame(delegate, recording.delegate)
    }

    @Test
    fun `each write is recorded with the settings and what the sink returned`() = runTest {
        val delegate = LogSink("remote", onFailure = SinkFailure.Drop(Duration.ZERO)) { entry, _ ->
            if (entry.message == "refused") Err(refused) else Ok(Unit)
        }
        val recording = RecordingSink(delegate)
        val settings = SinkConfig(LogLevel.DEBUG, Timestamp.Off)
        val _ = logger.addOrFail(recording, settings)
        logger.tag("app").i { "written" }
        logger.tag("app").i { "refused" }

        val written = recording.awaitCall()
        val failed = recording.awaitCall()

        assertEquals("written", written.entry.message)
        assertEquals(settings, written.config)
        assertEquals(Ok(Unit), written.result)
        assertEquals("refused", failed.entry.message)
        assertEquals(Err(refused), failed.result)
    }

    @Test
    fun `an entry retried under Retry is recorded once for each attempt`() = runTest {
        val attempts = AtomicInt(0)
        val delegate = LogSink("flaky", onFailure = SinkFailure.Retry(backoff = 10.milliseconds)) { _, _ ->
            if (attempts.incrementAndFetch() == 1) Err(refused) else Ok(Unit)
        }
        val recording = RecordingSink(delegate)
        val _ = logger.addOrFail(recording)
        logger.tag("app").i { "retried" }

        val first = recording.awaitCall()
        val second = recording.awaitCall()

        assertEquals(listOf<EmptyResult<SinkError>>(Err(refused), Ok(Unit)), listOf(first.result, second.result))
        assertEquals(listOf("retried", "retried"), listOf(first.entry.message, second.entry.message))
    }

    @Test
    fun `calls flows each call once in order and received keeps them all`() = runTest {
        val recording = RecordingSink(LogSink("plain") { _, _ -> Ok(Unit) })
        val _ = logger.addOrFail(recording)
        repeat(3) { logger.tag("app").i { "entry $it" } }

        assertEquals(List(3) { "entry $it" }, recording.calls.take(3).map { it.entry.message }.toList())
        assertEquals(List(3) { "entry $it" }, recording.received.map { it.entry.message })
    }

    @Test
    fun `a throw is recorded as Dropped with Thrown and reaches the caller as it is`() {
        val boom = IllegalStateException("boom")
        val recording = RecordingSink(LogSink("throws") { _, _ -> throw boom })

        val thrown = assertFailsWith<IllegalStateException> { val _ = recording.write(entry, SinkConfig()) }

        assertSame(boom, thrown)
        assertEquals(Err(SinkError.Dropped(Thrown(boom))), recording.received.single().result)
    }

    @Test
    fun `a throw through a logger is recorded and counted as dropped`() = runTest {
        val boom = IllegalStateException("boom")
        val throwing = LogSink("throws", onFailure = SinkFailure.Drop(Duration.ZERO)) { _, _ -> throw boom }
        val recording = RecordingSink(throwing)
        val installed = logger.addOrFail(recording)
        logger.tag("app").i { "thrown" }

        val call = recording.awaitCall()
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (installed.health.value.dropped == 0L) delay(5.milliseconds)
            }
        }

        assertEquals(Err(SinkError.Dropped(Thrown(boom))), call.result)
        assertEquals(SinkError.Dropped(Thrown(boom)), installed.health.value.lastError)
        assertEquals(1, installed.health.value.dropped)
    }

    @Test
    fun `close reaches the sink it wraps`() {
        val closes = AtomicInt(0)
        val delegate = object : LogSink {
            override val name = "holds a file"

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = Ok(Unit)

            override fun close(): EmptyResult<SinkError> {
                closes += 1
                return Err(refused)
            }
        }

        assertEquals(Err(refused), RecordingSink(delegate).close())
        assertEquals(1, closes.load())
    }

    @Test
    fun `awaitCall fails the test once its timeout of real time passes with no call`() = runTest {
        val recording = RecordingSink(LogSink("quiet") { _, _ -> Ok(Unit) })

        val took = TimeSource.Monotonic.measureTime {
            val failure = assertFailsWith<AssertionError> { val _ = recording.awaitCall(100.milliseconds) }
            assertContains(failure.message.orEmpty(), "quiet")
            assertContains(failure.message.orEmpty(), "100ms")
        }

        // Node's timers can fire up to a millisecond before their delay.
        assertTrue(took >= 95.milliseconds, "gave up after $took")
    }

    @Test
    fun `awaitCall waits in real time under runTest`() = runTest {
        val recording = RecordingSink(LogSink("late") { _, _ -> Ok(Unit) })
        val _ = logger.addOrFail(recording)
        launch(Dispatchers.Default) {
            delay(200.milliseconds)
            logger.tag("app").i { "late" }
        }

        assertEquals("late", recording.awaitCall(5.seconds).entry.message)
    }

    /** A batching sink counting its single writes and each batch's size, refusing every call when [refusing]. */
    @OptIn(InternalKernApi::class)
    private class Batches(private val refusing: SinkError? = null) : BatchSink {
        override val name = "batches"
        override val onFailure = SinkFailure.Drop(Duration.ZERO)
        private val lock = reentrantLock()
        private val sizes = mutableListOf<Int>()
        private var singles = 0

        val batchSizes: List<Int>
            get() = lock.withLock { sizes.toList() }

        val singleWrites: Int
            get() = lock.withLock { singles }

        override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
            lock.withLock { singles++ }
            return refusing?.let { Err(it) } ?: Ok(Unit)
        }

        override fun writeBatch(entries: List<LogEntry>, config: SinkConfig): EmptyResult<SinkError> {
            lock.withLock { sizes += entries.size }
            return refusing?.let { Err(it) } ?: Ok(Unit)
        }
    }

    /** Holds [count] entries and lets them go at once, so the worker finds them queued together. */
    private suspend fun logQueuedTogether(count: Int): Result<Flushed, FlushError> {
        logger.holdUntilConfigured()
        repeat(count) { logger.tag("app").i { "entry $it" } }
        return logger.awaitFlushed()
    }

    @Test
    fun `a batching sink gets queued entries in one write and each is recorded in order`() = runTest {
        val delegate = Batches()
        val recording = RecordingSink(delegate)
        val _ = logger.addOrFail(recording)

        val _ = logQueuedTogether(200)

        assertTrue(delegate.batchSizes.any { it > 1 }, "batches: ${delegate.batchSizes}")
        assertEquals(0, delegate.singleWrites)
        assertEquals(List(200) { "entry $it" }, recording.received.map { it.entry.message })
        assertTrue(recording.received.all { it.result == Ok(Unit) })
    }

    @Test
    fun `a sink that does not batch gets one write per entry and a refused one fails alone`() = runTest {
        val delegate = LogSink("single", onFailure = SinkFailure.Drop(Duration.ZERO)) { entry, _ ->
            if (entry.message == "entry 1") Err(refused) else Ok(Unit)
        }
        val recording = RecordingSink(delegate)
        val installed = logger.addOrFail(recording)

        val flushed = logQueuedTogether(3)

        assertEquals(Err(FlushError.Lost(mapOf("single" to 1L), emptyMap())), flushed)
        val calls = recording.received.filter { it.entry.tag == "app" }
        assertEquals(List(3) { "entry $it" }, calls.map { it.entry.message })
        assertEquals(listOf(Ok(Unit), Err(refused), Ok(Unit)), calls.map { it.result })
        assertEquals(1, installed.health.value.dropped)
    }

    @Test
    fun `a refused batch is recorded for each entry and counted as one failure per call`() = runTest {
        val delegate = Batches(refusing = refused)
        val recording = RecordingSink(delegate)
        val installed = logger.addOrFail(recording)

        val flushed = logQueuedTogether(5)

        assertEquals(Err(FlushError.Lost(mapOf("batches" to 5L), emptyMap())), flushed)
        val calls = recording.received.filter { it.entry.tag == "app" }
        assertEquals(List(5) { "entry $it" }, calls.map { it.entry.message })
        assertTrue(calls.all { it.result == Err(refused) })
        assertEquals(0, delegate.singleWrites)
        assertEquals(delegate.batchSizes.size.toLong(), installed.health.value.failures)
        assertEquals(5, installed.health.value.dropped)
    }
}

