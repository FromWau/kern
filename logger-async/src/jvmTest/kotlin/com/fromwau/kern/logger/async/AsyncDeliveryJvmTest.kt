package com.fromwau.kern.logger.async

import com.fromwau.kern.logger.ConsoleOutput
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.DropPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// An Error ends a native process outright, so only the JVM can show what the logger does after one.
class AsyncDeliveryJvmTest {
    private class Fatal : Error("sink overflowed its stack")

    @Test
    fun `close still returns after an Error has stopped the writer`() {
        val delivery = AsyncDelivery()
        val logger = Logger(sinks = listOf(LogSink { _, _ -> throw Fatal() }), delivery = delivery)
        logger.configure(LoggerConfig(console = ConsoleOutput.Off))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }

        try {
            logger.tag("app").i { "fatal for the writer" }
            logger.tag("app").i { "queued behind it" }
            val closing = thread { logger.close() }
            closing.join(5_000)

            assertFalse(closing.isAlive, "close() must not wait forever on a writer that has stopped")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `close returns after the newest entries were dropped and counts them`() {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val holding = LogSink { entry, _ ->
            if (entry.message == "hold") {
                started.complete(Unit)
                runBlocking { gate.await() }
            }
        }
        val delivery = AsyncDelivery(capacity = 2, drop = DropPolicy.NEWEST)
        val logger = Logger(sinks = listOf(holding), delivery = delivery)
        logger.configure(LoggerConfig(console = ConsoleOutput.Off))

        logger.tag("app").i { "hold" }
        runBlocking { started.await() }
        repeat(5) { logger.tag("app").i { "entry $it" } }
        gate.complete(Unit)
        val closing = thread { logger.close() }
        closing.join(5_000)

        assertFalse(closing.isAlive, "close() must not wait for entries that were dropped")
        assertEquals(3, delivery.health.value.dropped)
    }

    @Test
    fun `entries logged after an Error stopped the writer are counted as dropped`() {
        val delivery = AsyncDelivery()
        val logger = Logger(sinks = listOf(LogSink { _, _ -> throw Fatal() }), delivery = delivery)
        logger.configure(LoggerConfig(console = ConsoleOutput.Off))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }

        try {
            logger.tag("app").i { "fatal for the writer" }
            logger.close()
            repeat(3) { logger.tag("app").i { "after it" } }

            assertEquals(3, delivery.health.value.dropped)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
