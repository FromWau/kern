package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.runTest

class FlushCloseTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    /** A sink that fails every write and would wait [backoff] before retrying, already failing on its first entry. */
    private fun failingSink(
        name: String = "failing",
        backoff: Duration = 10.seconds,
        logger: Logger = this.logger,
    ): Pair<Recorder, InstalledSink> {
        val sink = Recorder(
            name = name,
            onFailure = SinkFailure.Retry(backoff = backoff),
            failWith = { _, _ -> refused },
        )
        return sink to logger.addOrFail(sink)
    }

    @Test
    fun `awaitFlushed makes a sink in a Retry backoff try again at once`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Retry(backoff = 10.seconds),
            failWith = { attempt, _ -> if (attempt == 1) refused else null },
        )
        val _ = logger.addOrFail(sink)
        logger.tag("app").i { "retried" }
        eventually { sink.attempts == 1 }

        val (flushed, took) = TimeSource.Monotonic.measureTimedValue { logger.awaitFlushed(5.seconds) }

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(listOf("retried"), sink.messages)
        assertTrue(took < 2.seconds, "flush waited $took through the backoff")
    }

    @Test
    fun `awaitFlushed gives up at a failed retry with that failure and keeps the entries queued`() = runTest {
        val (sink, installed) = failingSink()
        repeat(2) { logger.tag("app").i { "entry $it" } }
        eventually { installed.health.value.failingSince != null }

        val (flushed, took) = TimeSource.Monotonic.measureTimedValue { logger.awaitFlushed(5.seconds) }

        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.GaveUp(2, refused)))), flushed)
        assertTrue(took < 2.seconds, "flush waited $took for a failing sink")
        assertEquals(2, sink.attempts, "one attempt, then the one the flush asked for")
        assertEquals(2, installed.queued)
        assertEquals(0, installed.health.value.dropped)
    }

    @Test
    fun `close of a Retry sink that never recovers drops at its deadline exactly what it reports`() = runTest {
        val (sink, installed) = failingSink("stuck", backoff = 20.milliseconds)
        repeat(2) { logger.tag("app").i { "never written $it" } }
        eventually { installed.health.value.failingSince != null }

        val (closed, took) = TimeSource.Monotonic.measureTimedValue { logger.awaitClosed(300.milliseconds) }

        assertEquals(installed.closeFailed(SinkError.TimedOut(pending = 2)), closed)
        // Node's timers can fire up to a millisecond before their delay.
        assertTrue(took >= 295.milliseconds, "close gave up after $took, before its deadline")
        assertTrue(sink.attempts > 3, "close stopped retrying after ${sink.attempts} attempts")
        assertEquals(1, sink.closes)
        eventually { installed.health.value.dropped == 2L }
        assertEquals(0, installed.queued)
        val attempts = sink.attempts
        realDelay(50.milliseconds)
        assertEquals(attempts, sink.attempts, "the dropped entries were retried after close")
        assertEquals(listOf(installed), logger.sinks.value)
        val flushed = logger.awaitFlushed(Duration.ZERO)
        assertEquals(Ok(Flushed.FullyWritten), flushed, "the entries close dropped were reported again")
    }

    @Test
    fun `a close after one that timed out reports what that one dropped as lost`() = runTest {
        val failing = atomic(true)
        val sink = Recorder(
            name = "stuck",
            onFailure = SinkFailure.Retry(backoff = 20.milliseconds),
            failWith = { _, _ -> if (failing.value) refused else null },
        )
        val installed = logger.addOrFail(sink)
        repeat(2) { logger.tag("app").i { "dropped $it" } }
        eventually { installed.health.value.failingSince != null }
        assertEquals(installed.closeFailed(SinkError.TimedOut(pending = 2)), logger.awaitClosed(300.milliseconds))
        failing.value = false

        assertEquals(installed.closeFailed(SinkError.Lost(2)), logger.awaitClosed())
        assertEquals(installed.closeFailed(SinkError.Lost(2)), logger.close())
        assertEquals(installed.closeFailed(SinkError.Lost(2)), installed.close())
    }

    /** A sink that loses every entry whose message is "lost", and writes every other. */
    private fun losingSink(name: String = "losing") = Recorder(
        name = name,
        onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
        failWith = { _, entry -> if (entry.message == "lost") refused else null },
    )

    @Test
    fun `a flush reports a loss once and the next flush with nothing more lost returns Ok`() = runTest {
        val installed = logger.addOrFail(losingSink())
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }

        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1), stillQueued = emptyMap())), logger.awaitFlushed())
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
    }

    @Test
    fun `a flush that finds a loss also names each sink still holding entries`() = runTest {
        val losing = logger.addOrFail(losingSink())
        val (_, failing) = failingSink()
        logger.tag("app").i { "lost" }
        eventually { losing.health.value.dropped == 1L && failing.health.value.failingSince != null }

        val flushed = logger.awaitFlushed(5.seconds)

        val stillQueued = mapOf(failing.name to Pending.GaveUp(1, refused))
        assertEquals(Err(FlushError.Lost(mapOf(losing.name to 1), stillQueued)), flushed)
        assertEquals(1, failing.queued)
    }

    @Test
    fun `close reports every entry a sink lost since it was added and a flush only what is new`() = runTest {
        val installed = logger.addOrFail(losingSink())
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }
        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1), emptyMap())), logger.awaitFlushed())

        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 2L }
        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1), emptyMap())), logger.awaitFlushed())

        assertEquals(installed.closeFailed(SinkError.Lost(2)), logger.awaitClosed())
    }

    @Test
    fun `a flush after a close reports only what was lost since that close`() = runTest {
        val installed = logger.addOrFail(losingSink())
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }
        assertEquals(installed.closeFailed(SinkError.Lost(1)), logger.awaitClosed())

        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 2L }
        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1), emptyMap())), logger.awaitFlushed())
    }

    @Test
    fun `every close reports the whole loss again including the blocking one and the handle's`() = runTest {
        val installed = logger.addOrFail(losingSink())
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }

        val lost = installed.closeFailed(SinkError.Lost(1))
        assertEquals(lost, logger.awaitClosed())
        assertEquals(lost, installed.close())
        assertEquals(lost, logger.close())
    }

    @Test
    fun `a sink that wrote everything and lost nothing flushes and closes Ok`() = runTest {
        val sink = TestSink()
        val installed = logger.addOrFail(sink)
        repeat(20) { logger.tag("app").i { "entry $it" } }

        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(Ok(Unit), installed.close())
        assertEquals(List(20) { "entry $it" }, sink.messages)
    }

    @Test
    fun `a close reports what every sink lost and not only the first`() = runTest {
        val first = logger.addOrFail(losingSink("first"))
        val _ = logger.addOrFail(Recorder("fine"))
        val second = logger.addOrFail(losingSink("second"))
        logger.tag("app").i { "lost" }
        logger.tag("app").i { "written" }
        eventually { first.health.value.dropped == 1L && second.health.value.dropped == 1L }

        val closed = logger.awaitClosed()

        val lost = listOf(SinkError.Lost(1))
        assertEquals(Err(CloseError(mapOf(first.name to lost, second.name to lost))), closed)
    }

    @Test
    fun `close times out a failing sink and still closes every sink`() = runTest {
        val fine = Recorder("fine")
        val _ = logger.addOrFail(fine)
        val (stuck, installed) = failingSink("stuck")
        logger.tag("app").i { "entry" }
        eventually { installed.health.value.failingSince != null && fine.messages.isNotEmpty() }

        val (closed, took) = TimeSource.Monotonic.measureTimedValue { logger.awaitClosed(300.milliseconds) }

        assertEquals(installed.closeFailed(SinkError.TimedOut(pending = 1)), closed)
        assertTrue(took < 2.seconds, "close waited $took past its deadline")
        assertEquals(1, fine.closes)
        assertEquals(1, stuck.closes)
    }

    @Test
    fun `flush reports a removed sink still failing`() = runTest {
        val logger = testLogger(retireTimeout = 10.seconds)
        val (sink, installed) = failingSink(logger = logger)
        repeat(2) { logger.tag("app").i { "entry $it" } }
        eventually { installed.health.value.failingSince != null }
        logger.removeOrFail(sink)

        val flushed = logger.awaitFlushed(5.seconds)

        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.GaveUp(2, refused)))), flushed)
        assertEquals(2, installed.queued)
        val _ = logger.awaitClosed(Duration.ZERO)
    }

    @Test
    fun `close reports a removed sink still failing and counts what it dropped`() = runTest {
        val logger = testLogger(retireTimeout = 10.seconds)
        val (sink, installed) = failingSink("stuck", logger = logger)
        repeat(2) { logger.tag("app").i { "never written $it" } }
        eventually { installed.health.value.failingSince != null }
        logger.removeOrFail(sink)

        val (closed, took) = TimeSource.Monotonic.measureTimedValue { logger.awaitClosed(300.milliseconds) }

        assertEquals(installed.closeFailed(SinkError.TimedOut(pending = 2)), closed)
        assertTrue(took < 2.seconds, "close waited $took past its deadline")
        assertEquals(2, installed.health.value.dropped)
        eventually { sink.closes == 1 }
    }

    @Test
    fun `the blocking close reports a removed sink still failing too`() = runTest {
        val logger = testLogger(retireTimeout = 10.seconds)
        val (sink, installed) = failingSink("stuck", logger = logger)
        logger.tag("app").i { "never written" }
        eventually { installed.health.value.failingSince != null }
        logger.removeOrFail(sink)

        val closed = logger.close(Duration.ZERO)

        assertEquals(installed.closeFailed(SinkError.TimedOut(pending = 1)), closed)
        eventually { installed.health.value.dropped == 1L }
    }

    @Test
    fun `close reports what a removed sink lost after that sink finished closing`() = runTest {
        val sink = losingSink()
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }
        logger.removeOrFail(sink)
        eventually { installed.isDone }

        assertEquals(installed.closeFailed(SinkError.Lost(1)), logger.awaitClosed())
        assertEquals(installed.closeFailed(SinkError.Lost(1)), logger.close())
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed(), "a close reported the loss already")
    }

    @Test
    fun `close reports what a removed sink dropped when it gave up retrying`() = runTest {
        val logger = testLogger(retireTimeout = 200.milliseconds)
        val (sink, installed) = failingSink("stuck", backoff = 20.milliseconds, logger = logger)
        repeat(2) { logger.tag("app").i { "never written $it" } }
        eventually { installed.health.value.failingSince != null }
        logger.removeOrFail(sink)
        eventually { installed.isDone }

        assertEquals(installed.closeFailed(SinkError.Lost(2)), logger.awaitClosed())
    }

    @Test
    fun `a flush reports what a removed sink lost after it finished closing once`() = runTest {
        val sink = losingSink()
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "lost" }
        eventually { installed.health.value.dropped == 1L }
        logger.removeOrFail(sink)
        eventually { installed.isDone }

        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 1), emptyMap())), logger.awaitFlushed())
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
    }

    @Test
    fun `every sink of a name added and removed again reports its loss in one entry of that name`() = runTest {
        val handles = List(200) {
            val sink = losingSink("debug")
            val installed = logger.addOrFail(sink)
            logger.tag("app").i { "lost" }
            eventually { installed.health.value.dropped == 1L }
            logger.removeOrFail(sink)
            installed
        }
        eventually { handles.all { it.isDone } }

        assertEquals(Err(FlushError.Lost(mapOf("debug" to 200L), emptyMap())), logger.awaitFlushed())
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(Err(CloseError(mapOf("debug" to listOf(SinkError.Lost(200))))), logger.awaitClosed())
        assertEquals(Err(CloseError(mapOf("debug" to listOf(SinkError.Lost(200))))), logger.close())
    }

    @Test
    fun `closing the handle of a sink removed and closed for good returns ClosedAlready`() = runTest {
        val sink = Recorder("removed")
        val installed = logger.addOrFail(sink)
        logger.removeOrFail(sink)
        eventually { installed.isDone }

        assertEquals(installed.closeFailed(SinkError.ClosedAlready), installed.close())
        assertEquals(1, sink.closes)
    }

    @Test
    fun `close keeps retrying a Retry sink that fails now and then and loses nothing within its deadline`() = runTest {
        val sink = Recorder(
            name = "flaky",
            onFailure = SinkFailure.Retry(backoff = 2.milliseconds),
            failWith = { attempt, _ -> if (attempt % 7 == 0) refused else null },
        )
        val installed = logger.addOrFail(sink)
        repeat(700) { logger.tag("app").i { "entry $it" } }

        val closed = logger.awaitClosed(10.seconds)

        assertEquals(Ok(Unit), closed)
        assertEquals(List(700) { "entry $it" }, sink.messages)
        assertEquals(0, installed.health.value.dropped)
    }

    @Test
    fun `awaitClosed ends a hold and waits for every queue before closing each sink`() = runTest {
        val sink = Recorder()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()
        logger.tag("boot").i { "held" }
        repeat(50) { logger.tag("app").i { "entry $it" } }

        assertEquals(Ok(Unit), logger.awaitClosed())

        assertEquals(listOf("held") + List(50) { "entry $it" }, sink.messages)
        assertEquals(1, sink.closes)
        assertEquals(0, sink.writesAfterClose)
    }

    @Test
    fun `awaitClosed writes the gap left by dropped entries before closing and reports them lost`() = runTest {
        val sink = Recorder(
            onFailure = SinkFailure.Drop(backoff = 10.seconds),
            failWith = { attempt, _ -> if (attempt == 1) refused else null },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "dropped" }
        eventually { installed.health.value.dropped == 1L }

        val closed = logger.awaitClosed(100.milliseconds)

        assertEquals(installed.closeFailed(SinkError.Lost(1)), closed)

        val gap = sink.entries.single()
        assertEquals(INTERNAL_TAG, gap.tag)
        assertEquals(mapOf("dropped" to 1L), gap.fields)
        assertEquals(0, sink.writesAfterClose)
    }
}
