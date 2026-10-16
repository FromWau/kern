package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.WriteError
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SinkFailureTest {
    private val reports = mutableListOf<String>()
    private val logger = testLogger(reports)
    private val noBackoff = SinkFailure.Drop(backoff = Duration.ZERO)

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `Retry tries the failed entry again after the backoff without a new entry`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Retry(backoff = 10.milliseconds),
            failWith = { attempt, _ -> if (attempt <= 3) refused else null },
        )
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { "only entry" }

        eventually { sink.messages.isNotEmpty() }
        assertEquals(listOf("only entry"), sink.messages)
        assertEquals(4, sink.attempts)
    }

    @Test
    fun `Retry keeps the order of later entries behind the failed one`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Retry(backoff = 10.milliseconds),
            failWith = { attempt, _ -> if (attempt <= 2) refused else null },
        )
        val _ = logger.addOrFail(sink)

        listOf("a", "b", "c").forEach { logger.tag("app").i { it } }

        eventually { sink.messages.size == 3 }
        assertEquals(listOf("a", "b", "c"), sink.messages)
        assertEquals(5, sink.attempts, "two failures, then one write each")
    }

    @Test
    fun `Drop loses the failed entry and skips every entry during the backoff`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Drop(backoff = 500.milliseconds),
            failWith = { attempt, _ -> if (attempt == 1) refused else null },
        )
        val installed = logger.addOrFail(sink)

        logger.tag("app").i { "a" }
        eventually { sink.attempts == 1 }
        logger.tag("app").i { "b" }
        logger.tag("app").i { "c" }
        val _ = logger.awaitFlushed()

        assertEquals(1, sink.attempts, "the sink is not called during the backoff")
        assertTrue(sink.entries.isEmpty())
        assertEquals(3, installed.health.value.dropped)
    }

    @Test
    fun `a full queue drops the oldest waiting entries and counts them`() = runTest {
        val (sink, installed, open) = gatedSink(DropPolicy.OLDEST)

        listOf("b", "c", "d", "e").forEach { logger.tag("app").i { it } }
        assertEquals(2, installed.health.value.dropped)
        open()
        val _ = logger.awaitFlushed()

        assertEquals(2L, sink.droppedInGaps() + installed.pendingGap)
        assertEquals(listOf("a", "d", "e"), sink.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
    }

    @Test
    fun `a full queue under NEWEST drops the arriving entries and counts them`() = runTest {
        val (sink, installed, open) = gatedSink(DropPolicy.NEWEST)

        listOf("b", "c", "d", "e").forEach { logger.tag("app").i { it } }
        assertEquals(2, installed.health.value.dropped)
        open()
        val _ = logger.awaitFlushed()

        assertEquals(2L, sink.droppedInGaps() + installed.pendingGap)
        assertEquals(listOf("a", "b", "c"), sink.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
    }

    @Test
    fun `a console failure in health says which stream refused the line`() = runTest {
        val full = SinkError.Dropped(WriteError.Refused(Stream.Err, "No space left on device"))
        val installed = logger.addOrFail(Recorder(failWith = { _, _ -> full }))

        logger.tag("app").i { "lost" }
        val _ = logger.awaitFlushed()

        val error = assertIs<SinkError.Dropped>(installed.health.value.lastError)
        assertEquals(Stream.Err, assertIs<WriteError>(error.error).stream)
    }

    @Test
    fun `a sink that throws fails with Dropped carrying what it threw`() = runTest {
        val thrown = IllegalStateException("disk on fire")
        val sink = LogSink("throws") { _, _ -> throw thrown }
        val installed = logger.addOrFail(sink)

        logger.tag("app").i { "lost" }
        val _ = logger.awaitFlushed()

        assertEquals(SinkError.Dropped(Thrown(thrown)), installed.health.value.lastError)
        assertEquals(1, installed.health.value.failures)
    }

    @Test
    fun `failingSince is set by a failure and cleared by the next success while lastError stays`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            failWith = { _, entry -> if (entry.message == "fail") refused else null },
        )
        val installed = logger.addOrFail(sink)

        logger.tag("app").i { "fail" }
        val _ = logger.awaitFlushed()
        val failing = installed.health.value
        assertNotNull(failing.failingSince)
        assertEquals(refused, failing.lastError)

        logger.tag("app").i { "fine" }
        val _ = logger.awaitFlushed()
        val recovered = installed.health.value
        assertNull(recovered.failingSince)
        assertEquals(refused, recovered.lastError)
        assertEquals(1, recovered.failures)
    }

    @Test
    fun `only the first failure of a sink is reported on standard error`() = runTest {
        val sink = Recorder(
            name = "flaky",
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            failWith = { _, _ -> refused },
        )
        val _ = logger.addOrFail(sink)

        repeat(3) { logger.tag("app").i { "lost $it" } }
        val _ = logger.awaitFlushed()

        assertEquals(1, reports.size, "$reports")
        assertEquals("kern.logger: sink \"flaky\" failed: $refused", reports.single())
    }

    @Test
    fun `a failing sink reloaded under one name many times is reported once`() = runTest {
        repeat(50) {
            logger.replaceWith(Recorder(name = "flaky", onFailure = noBackoff, failWith = { _, _ -> refused }))
            logger.tag("app").i { "lost $it" }
            val _ = logger.awaitFlushed()
        }

        assertEquals(listOf("kern.logger: sink \"flaky\" failed: $refused"), reports)
    }

    @Test
    fun `a failure is reported again once a sink of that name has written`() = runTest {
        val failing = atomic(true)
        val _ = logger.addOrFail(
            Recorder(
                name = "flaky",
                onFailure = noBackoff,
                failWith = { _, _ -> refused.takeIf { failing.value } },
            ),
        )
        listOf(true, false, true).forEachIndexed { index, fails ->
            failing.value = fails
            logger.tag("app").i { "entry $index" }
            val _ = logger.awaitFlushed()
        }

        assertEquals(List(2) { "kern.logger: sink \"flaky\" failed: $refused" }, reports)
    }

    @Test
    fun `failing sinks of two names are reported once each`() = runTest {
        repeat(5) {
            logger.configure {
                LoggerConfig(
                    listOf("first", "second").map { name ->
                        ConfiguredSink(Recorder(name, onFailure = noBackoff, failWith = { _, _ -> refused }), allLevels)
                    },
                )
            }.assertSuccess()
            logger.tag("app").i { "lost $it" }
            val _ = logger.awaitFlushed()
        }

        assertEquals(listOf("first", "second").map { "kern.logger: sink \"$it\" failed: $refused" }, reports.sorted())
    }

    @Test
    fun `a sink that throws an Error fails with Dropped and its worker keeps writing`() = runTest {
        val thrown = NotImplementedError("not yet")
        val sink = Recorder(
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            onWrite = { if (it.message == "first") throw thrown },
        )
        val installed = logger.addOrFail(sink)

        logger.tag("app").i { "first" }
        logger.tag("app").i { "second" }

        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1L), emptyMap())), logger.awaitFlushed(2.seconds))
        assertEquals(SinkError.Dropped(Thrown(thrown)), installed.health.value.lastError)
        assertEquals(listOf("second"), sink.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
    }

    @Test
    fun `a close that throws an Error is returned as Dropped`() {
        val thrown = NotImplementedError("not yet")
        val sink = object : LogSink {
            override val name = "closer"

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = Ok(Unit)

            override fun close(): EmptyResult<SinkError> = throw thrown
        }
        val installed = logger.addOrFail(sink)

        assertEquals(installed.closeFailed(SinkError.Dropped(Thrown(thrown))), installed.close(Duration.ZERO))
    }

    @Test
    fun `a close that fails is a failure of the sink`() {
        val sink = object : LogSink {
            override val name = "unclosable"

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = Ok(Unit)

            override fun close(): EmptyResult<SinkError> = Err(refused)
        }
        val installed = logger.addOrFail(sink)

        assertEquals(installed.closeFailed(refused), installed.close(Duration.ZERO))

        val health = installed.health.value
        assertEquals(refused, health.lastError)
        assertTrue(health.failingSince != null, "failingSince stayed null")
        assertEquals(listOf("kern.logger: sink \"unclosable\" failed: $refused"), reports)
    }

    /** Records entries, and counts [lostOnClose] entries as dropped from inside each close, as a file sink may. */
    @OptIn(InternalKernApi::class)
    private class LosingOnClose(private val lostOnClose: Long, private val failGaps: Boolean = false) : LogSink {
        override val name = "losing"
        val written = mutableListOf<LogEntry>()
        var closes = 0
        var openAfterClose = false

        override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
            if (failGaps && entry.tag == INTERNAL_TAG) return Err(refused)
            written += entry
            openAfterClose = true
            return Ok(Unit)
        }

        override fun close(): EmptyResult<SinkError> {
            closes++
            openAfterClose = false
            if (closes == 1) reportDroppedBySink(lostOnClose, Instant.DISTANT_PAST, Instant.DISTANT_PAST)
            return Ok(Unit)
        }
    }

    @Test
    fun `entries a sink loses while it closes are reported by a gap entry and by close`() {
        val sink = LosingOnClose(lostOnClose = 3)
        val installed = logger.addOrFail(sink)

        assertEquals(installed.closeFailed(SinkError.Lost(3)), logger.close())

        val gap = sink.written.single()
        assertEquals(INTERNAL_TAG, gap.tag)
        assertEquals(mapOf("dropped" to 3L), gap.fields)
        assertEquals(3, installed.health.value.dropped)
        assertFalse(sink.openAfterClose, "the gap entry left the sink open")
    }

    @Test
    fun `a close that cannot write the gap entry for what it lost fails`() {
        val sink = LosingOnClose(lostOnClose = 3, failGaps = true)
        val installed = logger.addOrFail(sink)

        assertEquals(installed.closeFailed(refused, SinkError.Lost(3)), logger.close())
    }

    @Test
    fun `the gap entry reaches the sink that dropped whatever its level`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Drop(backoff = 500.milliseconds),
            failWith = { attempt, _ -> if (attempt == 1) refused else null },
        )
        val _ = logger.addOrFail(sink, SinkConfig(level = LogLevel.ERROR))

        logger.tag("app").e { "a" }
        eventually { sink.attempts == 1 }
        logger.tag("app").e { "b" }
        val _ = logger.awaitFlushed()
        realDelay(600.milliseconds)
        logger.tag("app").e { "c" }
        val _ = logger.awaitFlushed()

        val (gap, written) = sink.entries
        assertEquals(INTERNAL_TAG, gap.tag)
        assertEquals(LogLevel.WARN, gap.level)
        assertContains(gap.message, "dropped 2 entries")
        assertEquals(mapOf("dropped" to 2L), gap.fields)
        assertEquals("c", written.message)
    }

    // A retry may take the gap of the first drop just before the gate opens, so the count can arrive in two entries.
    private fun Recorder.droppedInGaps(): Long = entries
        .filter { it.tag == INTERNAL_TAG }
        .sumOf { it.fields["dropped"] as Long }

    /**
     * A sink with room for two that refuses everything until opened, with "a" already taken by its worker and
     * waiting for a retry.
     */
    private suspend fun gatedSink(drop: DropPolicy): Triple<Recorder, InstalledSink, () -> Unit> {
        val open = atomic(false)
        val sink = Recorder(
            onFailure = SinkFailure.Retry(backoff = 5.milliseconds),
            capacity = 2,
            drop = drop,
            failWith = { _, _ -> if (open.value) null else refused },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "a" }
        eventually { sink.attempts >= 1 }
        return Triple(sink, installed) { open.value = true }
    }
}
