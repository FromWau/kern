package com.fromwau.kern.logger

import com.fromwau.kern.result.Ok
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterruptedThreadJvmTest {
    private val logger = testLogger()
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)

    @AfterTest
    fun cleanup() {
        val _ = Thread.interrupted()
        release.countDown()
        logger.removeAll()
    }

    /** A sink stuck in its first write until [release], with [queued] entries logged to it. */
    private fun stuckSink(queued: Int): InstalledSink {
        val installed = logger.addOrFail(
            Recorder(
                name = "stuck",
                onWrite = {
                    started.countDown()
                    val _ = release.await(10, TimeUnit.SECONDS)
                },
            ),
        )
        repeat(queued) { logger.tag("app").i { "entry $it" } }
        assertTrue(started.await(5, TimeUnit.SECONDS), "the sink never started writing")
        return installed
    }

    @Test
    fun `flush on an interrupted thread returns what is pending and keeps the interrupt`() {
        val installed = stuckSink(queued = 2)

        Thread.currentThread().interrupt()
        val flushed = logger.flush()

        assertTrue(Thread.interrupted(), "the thread's interrupt was cleared")
        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.TimedOut(2)))), flushed)
    }

    @Test
    fun `close on an interrupted thread drops what is queued and keeps the interrupt`() = runTest {
        val installed = stuckSink(queued = 2)

        Thread.currentThread().interrupt()
        val closed = logger.close()

        assertTrue(Thread.interrupted(), "the thread's interrupt was cleared")
        assertEquals(installed.closeFailed(SinkError.TimedOut(2)), closed)
        release.countDown()
        eventually { installed.queued == 0 }
        assertEquals(1L, installed.health.value.dropped)
    }

    @Test
    fun `a sink's close on an interrupted thread drops what is queued and keeps the interrupt`() = runTest {
        val installed = stuckSink(queued = 2)

        Thread.currentThread().interrupt()
        val closed = installed.close()

        assertTrue(Thread.interrupted(), "the thread's interrupt was cleared")
        assertEquals(installed.closeFailed(SinkError.TimedOut(2)), closed)
        release.countDown()
        eventually { installed.queued == 0 }
        assertEquals(1L, installed.health.value.dropped)
    }
}
