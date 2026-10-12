package com.fromwau.kern.logger

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private class Boom : Error("boom")

/** A sink that throws while [failing] is set, and counts every call it gets. */
private class FlakySink(var failing: Boolean = true) : LogSink {
    val received = mutableListOf<String>()
    var calls = 0

    override fun write(entry: LogEntry, config: LoggerConfig) {
        calls++
        if (failing) throw IllegalStateException("sink is down")
        received += entry.message
    }
}

class LoggerFailureTest {
    private val time = TestTimeSource()
    private val reports = mutableListOf<String>()
    private val console = mutableListOf<String>()
    private val recorder = mutableListOf<String>()
    private val recording = LogSink { entry, _ -> recorder += entry.message }

    private fun logger(vararg sinks: LogSink, report: (String) -> Unit = { reports += it }) =
        Logger(sinks.toList(), { entry, _, _ -> console += entry.message }, report = report, timeSource = time)

    private fun config(sinkFailure: SinkFailure) = LoggerConfig(sinkFailure = sinkFailure)

    @Test
    fun `a failing sink loses the entry alone and the code that logged never sees it`() {
        val flaky = FlakySink()
        val logger = logger(flaky, recording)

        logger.tag("app").i { "first" }
        logger.tag("app").i { "second" }

        assertEquals(listOf("first", "second"), recorder)
        assertEquals(listOf("first", "second"), console)
        val health = logger.health.value.sinks[0]
        assertEquals(2, health.failures)
        assertEquals(2, health.dropped)
        assertEquals("sink is down", health.lastError?.message)
        assertEquals(1, reports.size, "a failing sink is reported once, not per entry: $reports")
        assertContains(reports.single(), "sink 0")
    }

    @Test
    fun `under SinkFailure Throw the failure reaches the caller after every other destination has the entry`() {
        val logger = logger(FlakySink(), recording)
        logger.configure(config(sinkFailure = SinkFailure.Throw))

        val thrown = assertFailsWith<IllegalStateException> { logger.tag("app").i { "loud" } }

        assertEquals("sink is down", thrown.message)
        assertEquals(listOf("loud"), recorder)
        assertEquals(listOf("loud"), console)
    }

    @Test
    fun `under SinkFailure Throw a second failure is suppressed into the first`() {
        val logger = logger(FlakySink(), FlakySink())
        logger.configure(config(sinkFailure = SinkFailure.Throw))

        val thrown = assertFailsWith<IllegalStateException> { logger.tag("app").i { "loud" } }

        assertEquals(1, thrown.suppressedExceptions.size)
    }

    @Test
    fun `a sink that keeps failing is skipped until the cooldown and closes again on a success`() {
        val flaky = FlakySink()
        val logger = logger(flaky)
        val breaker = Breaker.Trip(consecutiveFailures = 3, cooldown = 10.seconds)
        logger.configure(config(sinkFailure = SinkFailure.Drop(breaker)))

        repeat(5) { logger.tag("app").i { "entry $it" } }
        assertEquals(3, flaky.calls, "the breaker opens after three failures in a row")
        assertEquals(5, logger.health.value.sinks[0].dropped)
        assertTrue(logger.health.value.sinks[0].breakerOpen)

        time += 10.seconds
        logger.tag("app").i { "still failing" }
        logger.tag("app").i { "skipped again" }
        assertEquals(4, flaky.calls, "one retry after the cooldown, which fails and reopens it")

        flaky.failing = false
        time += 10.seconds
        logger.tag("app").i { "recovered" }
        logger.tag("app").i { "flowing" }

        assertEquals(listOf("recovered", "flowing"), flaky.received)
        assertFalse(logger.health.value.sinks[0].breakerOpen)
    }

    @Test
    fun `without a breaker every entry is offered however often the sink fails`() {
        val flaky = FlakySink()
        val logger = logger(flaky)
        logger.configure(config(sinkFailure = SinkFailure.Drop(Breaker.Off)))

        repeat(20) { logger.tag("app").i { "entry $it" } }

        assertEquals(20, flaky.calls)
    }

    @Test
    fun `a sink never receives an entry it logged while handling one`() {
        lateinit var logger: Logger
        val chatty = mutableListOf<String>()
        val chattySink = LogSink { entry, _ ->
            chatty += entry.message
            logger.tag("sink").w { "inner" }
        }
        logger = logger(chattySink, recording)

        logger.tag("app").i { "outer" }

        assertEquals(listOf("outer"), chatty)
        assertEquals(listOf("inner", "outer"), recorder)
    }

    @Test
    fun `a failing sink that logs its own failure does not loop`() {
        lateinit var logger: Logger
        var calls = 0
        val selfReporting = LogSink { _, _ ->
            calls++
            logger.tag("sink").e { "about to fail" }
            error("sink is down")
        }
        logger = logger(selfReporting, recording)

        logger.tag("app").i { "outer" }

        assertEquals(1, calls)
        assertEquals(listOf("about to fail", "outer"), recorder)
    }

    @Test
    fun `a sink's own cancellation is a failure like any other`() {
        val timingOut = LogSink { _, _ -> throw CancellationException("the sink's request timed out") }
        val logger = logger(timingOut, recording)

        logger.tag("app").i { "still delivered" }

        assertEquals(listOf("still delivered"), recorder)
        assertEquals(listOf("still delivered"), console)
        assertEquals(1, logger.health.value.sinks[0].failures)
    }

    @Test
    fun `an Error thrown by a sink is never caught`() {
        val logger = logger(LogSink { _, _ -> throw Boom() }, recording)

        assertFailsWith<Boom> { logger.tag("app").i { "fatal" } }
        assertEquals(0, logger.health.value.sinks[0].failures)
    }

    @Test
    fun `a report that fails is swallowed rather than reaching the caller`() {
        val logger = logger(FlakySink(), report = { error("standard error is closed") })

        logger.tag("app").i { "still fine" }

        assertEquals(listOf("still fine"), console)
    }

    @Test
    fun `a sink limited to errors is never offered the rest and its breaker still trips`() {
        val flaky = FlakySink()
        val logger = logger(flaky.atLeast(LogLevel.ERROR))
        val breaker = Breaker.Trip(consecutiveFailures = 3, cooldown = 10.seconds)
        logger.configure(config(sinkFailure = SinkFailure.Drop(breaker)))

        repeat(10) {
            logger.tag("app").i { "routine $it" }
            logger.tag("app").e { "failure $it" }
        }

        assertEquals(3, flaky.calls, "only errors reach it, and three failures in a row open the breaker")
        assertTrue(logger.health.value.sinks[0].breakerOpen)
        assertEquals(10, logger.health.value.sinks[0].dropped, "entries below its level are not dropped, just not its")
    }

    /** Writes later, on [flush], as a background writer would, but on the test's own thread. */
    private class Deferred : LogDelivery {
        private val queued = mutableListOf<() -> Unit>()

        override fun submit(write: () -> Unit) {
            queued += write
        }

        override fun flush() {
            queued.forEach { write -> runCatching(write) }
            queued.clear()
        }
    }

    @Test
    fun `a failure meant for the caller is reported once when the delivery cannot hand it back`() {
        val logger = Logger(listOf(FlakySink()), { _, _, _ -> }, Deferred(), { reports += it }, time)
        logger.configure(config(sinkFailure = SinkFailure.Throw))

        repeat(3) { logger.tag("app").i { "lost to a broken sink" } }
        logger.close()

        assertEquals(1, reports.size, "reported once, not per entry: $reports")
        assertContains(reports.single(), "cannot reach the code that logged")
    }

    @Test
    fun `close makes a last attempt for a retrying sink and reports what it still holds`() {
        val down = FlakySink()
        val up = FlakySink()
        val stillDown = RetryingSink(down, capacity = 10, backoff = 1.seconds)
        val logger = logger(stillDown, RetryingSink(up).atLeast(LogLevel.INFO))

        logger.tag("app").i { "a" }
        logger.tag("app").i { "b" }
        up.failing = false
        logger.close()

        assertEquals(listOf("a", "b"), up.received, "a sink that is back gets what it held")
        assertContains(reports.last(), "sink 0: 2 entries could not be delivered")
    }
}
