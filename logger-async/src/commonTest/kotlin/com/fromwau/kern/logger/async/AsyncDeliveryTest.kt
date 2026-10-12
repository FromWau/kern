package com.fromwau.kern.logger.async

import com.fromwau.kern.logger.ConsoleOutput
import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkFailure
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AsyncDeliveryTest {
    private val lock = reentrantLock()
    private val received = mutableListOf<String>()

    private val started = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    /** Records every entry, holding the writer at the first one named "hold" until [gate] opens. */
    private val recording = LogSink { entry, _ ->
        if (entry.message == "hold") {
            started.complete(Unit)
            runBlocking { gate.await() }
        }
        lock.withLock { received += entry.message }
    }

    private fun quiet(logger: Logger) = logger.configure(LoggerConfig(console = ConsoleOutput.Off))

    private fun received(): List<String> = lock.withLock { received.toList() }

    @Test
    fun `entries reach the sinks in order once the writer gets to them`() {
        val logger = Logger(sinks = listOf(recording), delivery = AsyncDelivery())
        quiet(logger)

        repeat(100) { logger.tag("app").i { "entry $it" } }
        logger.close()

        assertEquals((0 until 100).map { "entry $it" }, received())
    }

    @Test
    fun `a log call returns while the writer is still busy`() {
        val logger = Logger(sinks = listOf(recording), delivery = AsyncDelivery())
        quiet(logger)

        logger.tag("app").i { "hold" }
        runBlocking { started.await() }
        logger.tag("app").i { "queued behind it" }
        assertEquals(emptyList(), received(), "nothing is written while the writer is held")

        gate.complete(Unit)
        logger.close()

        assertEquals(listOf("hold", "queued behind it"), received())
    }

    @Test
    fun `a full queue drops its oldest entries and counts them`() {
        val delivery = AsyncDelivery(capacity = 3, drop = DropPolicy.OLDEST)
        val logger = Logger(sinks = listOf(recording), delivery = delivery)
        quiet(logger)

        logger.tag("app").i { "hold" }
        runBlocking { started.await() }
        repeat(5) { logger.tag("app").i { "entry $it" } }
        gate.complete(Unit)
        logger.close()

        assertEquals(listOf("hold", "entry 2", "entry 3", "entry 4"), received())
        assertEquals(2, delivery.health.value.dropped)
    }

    @Test
    fun `a failure meant for the caller is counted instead`() {
        val delivery = AsyncDelivery()
        val logger = Logger(sinks = listOf(LogSink { _, _ -> error("sink is down") }), delivery = delivery)
        logger.configure(LoggerConfig(console = ConsoleOutput.Off, sinkFailure = SinkFailure.Throw))

        logger.tag("app").i { "lost" }
        logger.close()

        assertEquals(1, delivery.health.value.failed)
        assertNotNull(delivery.health.value.lastError)
    }

    @Test
    fun `a cancellation thrown by a sink is counted and the writer keeps going`() {
        val delivery = AsyncDelivery()
        val cancelling = LogSink { entry, _ ->
            if (entry.message == "cancelled") throw CancellationException("not the writer's")
        }
        val logger = Logger(sinks = listOf(cancelling, recording), delivery = delivery)
        logger.configure(LoggerConfig(console = ConsoleOutput.Off, sinkFailure = SinkFailure.Throw))

        logger.tag("app").i { "cancelled" }
        logger.tag("app").i { "after it" }
        logger.close()

        assertEquals(listOf("cancelled", "after it"), received(), "the other sink still gets the entry")
        assertEquals(1, delivery.health.value.failed)
    }

    @Test
    fun `an entry a sink logs while writing never comes back to it`() {
        lateinit var logger: Logger
        var calls = 0
        val forwarding = LogSink { entry, _ ->
            calls++
            logger.tag("sink").i { "forwarded ${entry.message}" }
        }
        logger = Logger(sinks = listOf(forwarding, recording), delivery = AsyncDelivery())
        quiet(logger)

        logger.tag("app").i { "outer" }
        logger.close()

        assertEquals(1, calls)
        assertEquals(listOf("forwarded outer", "outer"), received())
    }

    @Test
    fun `entries logged before a hold keep the config they were logged under`() {
        val logger = Logger(sinks = listOf(recording), delivery = AsyncDelivery())
        quiet(logger)

        logger.tag("app").i { "hold" }
        runBlocking { started.await() }
        logger.tag("app").i { "logged before the hold" }
        gate.complete(Unit)
        logger.holdUntilConfigured()
        logger.configure(LoggerConfig(level = LogLevel.WARN, console = ConsoleOutput.Off))
        logger.close()

        assertEquals(listOf("hold", "logged before the hold"), received())
    }
}
