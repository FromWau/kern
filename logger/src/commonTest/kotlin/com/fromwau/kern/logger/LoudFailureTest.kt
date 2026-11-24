package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.ResultError
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A throw from a sink, or from a value it renders, is written with its stack trace to the other sinks and stderr. */
class LoudFailureTest {
    private val reports = mutableListOf<String>()
    private val logger = testLogger(reports)
    private val noBackoff = SinkFailure.Drop(backoff = Duration.ZERO)

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `a write that throws reaches the other sinks with what it threw`() = runTest {
        val thrown = IllegalStateException("disk on fire")
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(LogSink("throws") { _, _ -> throw thrown })

        logger.tag("app").i { "lost" }
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertEquals(LogLevel.ERROR, report.level)
        assertContains(report.message, "sink \"throws\"")
        assertSame(thrown, report.throwable)
    }

    @Test
    fun `a write that throws is reported to stderr with its stack trace`() = runTest {
        val thrown = IllegalStateException("disk on fire")
        val _ = logger.addOrFail(LogSink("throws") { _, _ -> throw thrown })

        logger.tag("app").i { "lost" }
        val _ = logger.awaitFlushed()

        val report = reports.single()
        assertContains(report, "sink \"throws\"")
        assertContains(report, thrown.stackTraceToString())
    }

    @Test
    fun `a sink that throws on every write is reported once until it writes again`() = runTest {
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(LogSink("throws", onFailure = noBackoff) { _, _ -> error("always") })

        repeat(5) { logger.tag("app").i { "lost $it" } }
        val _ = logger.awaitFlushed()

        assertEquals(1, other.received.count { it.tag == INTERNAL_TAG })
        assertEquals(1, reports.size, "$reports")
    }

    @Test
    fun `a close that throws reaches the other sinks and stderr with what it threw`() = runTest {
        val thrown = IllegalStateException("close refused")
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val closer = logger.addOrFail(
            object : LogSink {
                override val name = "closer"

                override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = Ok(Unit)

                override fun close(): EmptyResult<SinkError> = throw thrown
            },
        )

        val _ = closer.close(Duration.ZERO)
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertContains(report.message, "sink \"closer\"")
        assertSame(thrown, report.throwable)
        assertContains(reports.single(), thrown.stackTraceToString())
    }

    @Test
    fun `a batch write that throws reaches the other sinks with what it threw`() = runTest {
        val thrown = IllegalStateException("batch refused")
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(BatchRecorder(onBatch = { throw thrown }))

        logger.tag("app").i { "lost" }
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertContains(report.message, "sink \"batches\"")
        assertSame(thrown, report.throwable)
    }

    @Test
    fun `a message block that throws reaches the sinks and stderr with what it threw`() = runTest {
        val thrown = IllegalStateException("no message today")
        val sink = TestSink()
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { throw thrown }
        val _ = logger.awaitFlushed()

        val report = sink.received.single { it.tag == INTERNAL_TAG }
        assertEquals(LogLevel.ERROR, report.level)
        assertContains(report.message, "tagged \"app\"")
        assertSame(thrown, report.throwable)
        assertContains(reports.single(), thrown.stackTraceToString())
    }

    @Test
    fun `a message block that throws leaves the throwable the call was given as it was`() = runTest {
        val cause = IllegalArgumentException("bad input")
        val sink = TestSink()
        val _ = logger.addOrFail(sink)

        logger.tag("app").e(cause) { error("no message today") }
        val _ = logger.awaitFlushed()

        assertSame(cause, sink.received.single { it.tag == "app" }.throwable)
        assertEquals(emptyList(), cause.suppressedExceptions)
    }

    @Test
    fun `a message block that throws inside a sink's write is reported to every sink but that one`() = runTest {
        val other = TestSink("other")
        val chatty = Recorder(
            name = "chatty",
            onWrite = { entry -> if (entry.message == "trigger") logger.tag("chatty").i { error("no message") } },
        )
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(chatty)

        logger.tag("app").i { "trigger" }
        eventually { other.received.any { it.tag == INTERNAL_TAG } }
        realDelay(50.milliseconds)
        val _ = logger.awaitFlushed()

        assertEquals(listOf("trigger"), chatty.messages)
    }

    @Test
    fun `a field whose toString throws is reported naming the sink and the field`() = runTest {
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(renderingSink())

        logger.tag("app").i("value" to ToStringThrows) { "saved" }
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertEquals(LogLevel.ERROR, report.level)
        assertContains(report.message, "sink \"renders\"")
        assertContains(report.message, "field \"value\"")
        assertSame(ToStringThrows.thrown, report.throwable)
        assertContains(reports.single(), ToStringThrows.thrown.stackTraceToString())
    }

    @Test
    fun `a field that keeps throwing is reported once`() = runTest {
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(renderingSink())

        repeat(3) { logger.tag("app").i("value" to ToStringThrows) { "saved $it" } }
        val _ = logger.awaitFlushed()

        assertEquals(1, other.received.count { it.tag == INTERNAL_TAG })
        assertEquals(1, reports.size, "$reports")
    }

    @Test
    fun `an error whose toString throws is reported naming the error`() = runTest {
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(renderingSink())

        logger.tag("app").e(ErrorToStringThrows) { "saving failed" }
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertContains(report.message, "error")
        assertSame(ErrorToStringThrows.thrown, report.throwable)
    }

    @Test
    fun `a throwable whose stack trace throws is reported naming the throwable`() = runTest {
        val other = TestSink("other")
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(renderingSink())
        val hostile = MessageThrows()

        logger.tag("app").e(hostile) { "saving failed" }
        val _ = logger.awaitFlushed()

        val report = other.received.single { it.tag == INTERNAL_TAG }
        assertContains(report.message, "throwable")
        assertSame(hostile.thrown, report.throwable)
    }

    @Test
    fun `a failure report that throws loses only that report and the logger keeps writing`() = runTest {
        val lock = reentrantLock()
        val kept = mutableListOf<String>()
        var calls = 0
        @OptIn(InternalKernApi::class)
        logger.reportFailuresTo { report ->
            lock.withLock {
                if (++calls == 1) throw NotImplementedError("stderr is gone")
                kept += report
            }
        }
        val sink = Recorder(
            name = "flaky",
            onFailure = noBackoff,
            failWith = { _, entry -> if (entry.message.startsWith("fail")) refused else null },
        )
        val _ = logger.addOrFail(sink)

        listOf("fail 1", "written 1", "fail 2", "written 2").forEach { logger.tag("app").i { it } }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("written 1", "written 2"), sink.messages.filter { it.startsWith("written") })
        assertEquals(listOf("kern.logger: sink \"flaky\" failed: $refused"), kept)
    }

    /** Renders each entry as a text line, as the built-in sinks do. */
    @OptIn(InternalKernApi::class)
    private fun renderingSink(): LogSink {
        val render = lineRenderer(LogFormat.TEXT)
        return LogSink("renders", onFailure = noBackoff) { entry, config ->
            val _ = render(entry, config.timestamp)
            Ok(Unit)
        }
    }

    private object ToStringThrows {
        val thrown = IllegalStateException("no text")

        override fun toString(): String = throw thrown
    }

    private object ErrorToStringThrows : ResultError {
        val thrown = IllegalStateException("no text")

        override fun toString(): String = throw thrown
    }

    private class MessageThrows : Exception() {
        val thrown = IllegalStateException("no message")

        override val message: String
            get() = throw thrown
    }
}
