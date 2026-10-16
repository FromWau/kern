package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.Ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest

class HoldTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `held entries reach no sink until flush`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()

        logger.tag("boot").i { "reading config" }
        realDelay(50.milliseconds)
        assertTrue(sink.received.isEmpty(), "nothing may be written while held")

        val _ = logger.flush()
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(listOf("reading config"), sink.messages)
    }

    @Test
    fun `a held entry builds its message at once`() {
        val _ = logger.addOrFail(TestSink(), SinkConfig(LogLevel.ERROR))
        logger.holdUntilConfigured()
        var built = 0

        logger.tag("boot").v { "built ${++built}" }

        assertEquals(1, built)
    }

    @Test
    fun `the replay is filtered by each sink's level at the time of the flush`() = runTest {
        val debug = TestSink("debug")
        val warn = TestSink("warn")
        val _ = logger.addOrFail(debug, SinkConfig(LogLevel.INFO))
        val _ = logger.addOrFail(warn, SinkConfig(LogLevel.WARN))
        logger.holdUntilConfigured()

        logger.tag("boot").d { "detail" }
        logger.tag("boot").w { "warning" }
        logger.configureOrFail(debug) { it.copy(level = LogLevel.DEBUG) }
        val _ = logger.flush()
        val _ = logger.awaitFlushed()

        assertEquals(listOf("detail", "warning"), debug.messages)
        assertEquals(listOf("warning"), warn.messages)
    }

    @Test
    fun `the replay goes to the sinks configured when the hold ends`() = runTest {
        val removed = TestSink("removed")
        val added = TestSink("added")
        val _ = logger.addOrFail(removed)
        logger.holdUntilConfigured()

        logger.tag("boot").i { "held" }
        assertEquals(Ok(Unit), logger.configure { LoggerConfig(listOf(ConfiguredSink(added, allLevels))) })
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())

        assertEquals(listOf("held"), added.messages)
        assertTrue(removed.messages.isEmpty())
    }

    @Test
    fun `entries logged after the flush follow the replay`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()

        logger.tag("boot").i { "held" }
        val _ = logger.flush()
        logger.tag("app").i { "live" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("held", "live"), sink.messages)
    }

    @Test
    fun `past 1024 held entries the oldest give way and the replay says how many`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()

        repeat(1030) { index -> logger.tag("boot").i { "entry $index" } }
        assertEquals(6, logger.heldDropped.value)
        val _ = logger.flush()
        val _ = logger.awaitFlushed()

        val warning = sink.received.first()
        assertEquals(LogLevel.WARN, warning.level)
        assertEquals(INTERNAL_TAG, warning.tag)
        assertContains(warning.message, "dropped the 6 oldest held entries")
        assertEquals((6..1029).map { "entry $it" }, sink.messages.drop(1))
    }

    @Test
    fun `an entry a sink logs from inside its write during a hold is never replayed to that sink`() = runTest {
        val other = TestSink("other")
        val logged = atomic(false)
        val chatty = Recorder(
            name = "chatty",
            onWrite = { entry ->
                if (entry.message == "trigger") {
                    logger.holdUntilConfigured()
                    logger.tag("chatty").i { "logged from inside write" }
                    logged.value = true
                }
            },
        )
        val _ = logger.addOrFail(chatty)
        val _ = logger.addOrFail(other)

        logger.tag("app").i { "trigger" }
        eventually { logged.value }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("trigger"), chatty.messages)
        assertEquals(listOf("trigger", "logged from inside write"), other.messages)
    }

    @Test
    fun `awaitFlushed ends a hold and waits for the replay`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()

        logger.tag("boot").i { "held" }

        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(listOf("held"), sink.messages)
    }
}
