package com.fromwau.kern.logger.test

import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.measureTime

class TestSinkTest {
    private val logger = emptyLogger()

    @AfterTest
    fun cleanup() {
        val _ = logger.close()
    }

    @Test
    fun `awaitEntry hands out each entry once in the order logged`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        repeat(100) { logger.tag("app").i { "entry $it" } }

        val messages = List(100) { sink.awaitEntry().message }

        assertEquals(List(100) { "entry $it" }, messages)
    }

    @Test
    fun `entries flows each entry once and awaitEntry goes on after them`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        repeat(3) { logger.tag("app").i { "entry $it" } }

        assertEquals(List(3) { "entry $it" }, sink.entries.take(3).map { it.message }.toList())
        logger.tag("app").i { "after" }
        assertEquals("after", sink.awaitEntry().message)
    }

    @Test
    fun `awaitEntry waits in real time under runTest`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        launch(Dispatchers.Default) {
            delay(200.milliseconds)
            logger.tag("app").i { "late" }
        }

        assertEquals("late", sink.awaitEntry(5.seconds).message)
    }

    @Test
    fun `awaitEntry fails the test once its timeout of real time passes with no entry`() = runTest {
        val sink = TestSink("quiet")

        val took = TimeSource.Monotonic.measureTime {
            val failure = assertFailsWith<AssertionError> { val _ = sink.awaitEntry(100.milliseconds) }
            assertContains(failure.message.orEmpty(), "quiet")
            assertContains(failure.message.orEmpty(), "100ms")
        }

        // Node's timers can fire up to a millisecond before their delay.
        assertTrue(took >= 95.milliseconds, "gave up after $took")
    }

    @Test
    fun `received keeps every entry after awaitEntry took them`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.tag("app").i { "first" }
        logger.tag("app").w { "second" }

        val _ = sink.awaitEntry()
        val _ = sink.awaitEntry()

        assertEquals(listOf("first", "second"), sink.received.map { it.message })
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN), sink.received.map { it.level })
    }

    @Test
    fun `received is a snapshot that later entries leave as it was`() {
        val sink = TestSink()
        val entry = LogEntry(Instant.fromEpochSeconds(0), "app", LogLevel.INFO, "first")
        assertEquals(Ok(Unit), sink.write(entry, SinkConfig()))
        val snapshot = sink.received

        assertEquals(Ok(Unit), sink.write(entry.copy(message = "second"), SinkConfig()))

        assertEquals(listOf(entry), snapshot)
        assertEquals(listOf("first", "second"), sink.received.map { it.message })
    }

    @Test
    fun `a TestSink is named test unless you name it`() {
        assertEquals("test", TestSink().name)
        assertEquals("audit", TestSink("audit").name)
    }
}
