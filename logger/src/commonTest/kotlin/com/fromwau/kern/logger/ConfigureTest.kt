package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ConfigureTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `configured sinks are listed in the order of the config`() {
        val first = Recorder("first")
        val second = Recorder("second")

        val configured = logger.configure {
            LoggerConfig(listOf(ConfiguredSink(first, allLevels), ConfiguredSink(second)))
        }
        assertEquals(Ok(Unit), configured)

        assertEquals(listOf(ConfiguredSink(first, allLevels), ConfiguredSink(second)), logger.config.value.sinks)
        assertEquals(listOf<LogSink>(first, second), logger.sinks.value.map { it.sink })
        assertEquals(listOf("first", "second"), logger.sinks.value.map { it.name })
        assertEquals(SinkConfig(LogLevel.VERBOSE), logger.sinks.value.first().config.value)
    }

    @Test
    fun `a new Logger starts with one PlatformSink`() {
        val fresh = Logger()

        val (sink, config) = fresh.config.value.sinks.single()
        assertIs<PlatformSink>(sink)
        assertEquals(SinkConfig(), config)
        assertIs<PlatformSink>(fresh.sinks.value.single().sink)
        fresh.removeAll()
    }

    @Test
    fun `two sinks of one name are refused and nothing changes`() = runTest {
        val kept = Recorder("app")
        val _ = logger.addOrFail(kept)
        val before = logger.sinks.value

        val refused = logger.configure { it.copy(sinks = it.sinks + ConfiguredSink(Recorder("app"), allLevels)) }
        logger.tag("app").i { "still delivered" }
        val _ = logger.awaitFlushed()

        assertEquals(Err(ConfigError.NameTaken("app")), refused)
        assertEquals(listOf<LogSink>(kept), logger.config.value.sinks.map { it.sink })
        assertSame(before, logger.sinks.value)
        assertEquals(listOf("still delivered"), kept.messages)
    }

    @Test
    fun `the same sink twice in the list is refused as a taken name`() {
        val sink = Recorder("twice")

        val twice = logger.configure { LoggerConfig(listOf(ConfiguredSink(sink), ConfiguredSink(sink))) }
        assertEquals(Err(ConfigError.NameTaken("twice")), twice)
        assertTrue(logger.sinks.value.isEmpty())
    }

    @Test
    fun `a capacity below one is refused as invalid and nothing changes`() {
        val kept = logger.addOrFail(Recorder("kept"))

        listOf(0, -1).forEach { capacity ->
            val small = ConfiguredSink(Recorder("small", capacity = capacity), allLevels)
            val error = logger.configure { it.copy(sinks = it.sinks + small) }

            val invalid = error.assertError<ConfigError.Invalid>()
            assertEquals("small", invalid.name)
        }
        assertEquals(listOf(kept), logger.sinks.value)
    }

    @Test
    fun `a negative backoff is refused as invalid`() {
        val failures = listOf(SinkFailure.Drop((-1).milliseconds), SinkFailure.Retry((-1).milliseconds))

        failures.forEach { failure ->
            val negative = ConfiguredSink(Recorder("negative", onFailure = failure), allLevels)
            val error = logger.configure { LoggerConfig(listOf(negative)) }

            error.assertError<ConfigError.Invalid>()
        }
        assertTrue(logger.sinks.value.isEmpty())
    }

    @Test
    fun `a sink whose property throws is refused as invalid rather than throwing`() {
        val broken = object : LogSink {
            override val name = "broken"
            override val capacity: Int
                get() = error("no capacity")

            override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = Ok(Unit)
        }

        logger.configure { LoggerConfig(listOf(ConfiguredSink(broken, allLevels))) }.assertError<ConfigError.Invalid>()
        assertTrue(logger.sinks.value.isEmpty())
    }

    @Test
    fun `a sink in both lists keeps its handle settings and health`() = runTest {
        val kept = Recorder(
            name = "kept",
            onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
            failWith = { attempt, _ -> if (attempt == 1) refused else null },
        )
        val installed = logger.addOrFail(kept)
        logger.configureOrFail(kept) { it.copy(level = LogLevel.WARN) }
        logger.tag("app").w { "fails once" }
        val _ = logger.awaitFlushed()

        val _ = logger.addOrFail(Recorder("added"))
        logger.tag("app").i { "below kept's level" }
        logger.tag("app").w { "written" }
        val _ = logger.awaitFlushed()

        assertSame(installed, logger.sinks.value.first())
        assertEquals(LogLevel.WARN, installed.config.value.level)
        assertEquals(1, installed.health.value.failures)
        assertEquals(listOf("written"), kept.entries.filter { it.tag != INTERNAL_TAG }.map { it.message })
    }

    @Test
    fun `a removed sink gets no further entries and is closed once drained`() = runTest {
        val removed = Recorder("removed")
        val _ = logger.addOrFail(removed)
        logger.tag("app").i { "before" }

        logger.removeOrFail(removed)
        logger.tag("app").i { "after" }
        eventually { removed.closes == 1 }

        assertTrue(logger.sinks.value.isEmpty())
        assertEquals(listOf("before"), removed.messages)
    }

    @Test
    fun `a removed sink leaves sinks at once and its handle shows it draining`() = runTest {
        val failing = atomic(true)
        val sink = Recorder(
            name = "draining",
            onFailure = SinkFailure.Retry(backoff = 5.milliseconds),
            failWith = { _, _ -> if (failing.value) refused else null },
        )
        val settings = SinkConfig(LogLevel.DEBUG, Timestamp.Off)
        val installed = logger.addOrFail(sink, settings)
        repeat(3) { logger.tag("app").i { "queued $it" } }
        eventually { installed.health.value.failingSince != null }

        logger.removeOrFail(sink)

        assertTrue(logger.sinks.value.isEmpty())
        assertEquals(3, installed.queued)
        assertEquals(settings, installed.config.value)
        val failures = installed.health.value.failures
        eventually { installed.health.value.failures > failures }

        failing.value = false
        eventually { installed.isDone }
        assertEquals(0, installed.queued)
        assertEquals(null, installed.health.value.failingSince)
        assertEquals(settings, installed.config.value)
        assertEquals(List(3) { "queued $it" }, sink.messages)
        assertEquals(installed.closeFailed(SinkError.ClosedAlready), installed.close())
    }

    @Test
    fun `configure re-levels a sink in place and config shows the settings in force`() = runTest {
        val sink = Recorder("sink")
        val installed = logger.addOrFail(sink, SinkConfig(LogLevel.WARN))
        val quiet = SinkConfig(LogLevel.ERROR, Timestamp.Off)

        logger.configureOrFail(sink) { quiet }
        logger.tag("app").w { "below the new level" }
        logger.tag("app").e { "written" }
        val _ = logger.awaitFlushed()

        assertSame(installed, logger.sinks.value.single())
        assertEquals(listOf(ConfiguredSink(sink, quiet)), logger.config.value.sinks)
        assertEquals(quiet, installed.config.value)
        assertEquals(listOf("written"), sink.messages)
    }

    @Test
    fun `a sink added back after it was closed gets a new handle at the settings it is paired with now`() = runTest {
        val sink = Recorder("returning")
        val first = logger.addOrFail(sink)
        logger.removeOrFail(sink)
        eventually { first.state.value == SinkState.Closed }

        val second = logger.addOrFail(sink, SinkConfig(LogLevel.WARN))
        logger.tag("app").i { "below the new level" }
        logger.tag("app").w { "written" }
        val _ = logger.awaitFlushed()

        assertTrue(first !== second, "the closed handle came back")
        assertEquals(SinkConfig(LogLevel.WARN), second.config.value)
        assertEquals(listOf("written"), sink.messages)
        assertEquals(SinkState.Closed, first.state.value)
        assertEquals(SinkState.Active, second.state.value)
    }

    @Test
    fun `a configured sink is Active`() {
        val installed = logger.addOrFail(Recorder("active"))

        assertEquals(SinkState.Active, installed.state.value)
    }

    @Test
    fun `a removed sink is Draining until its queue is written and Closed once its close ran`() = runTest {
        val failing = atomic(true)
        val sink = Recorder(
            name = "draining",
            onFailure = SinkFailure.Retry(backoff = 5.milliseconds),
            failWith = { _, _ -> if (failing.value) refused else null },
        )
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "queued" }
        eventually { sink.attempts >= 1 }

        logger.removeOrFail(sink)
        assertEquals(SinkState.Draining, installed.state.value)
        assertEquals("InstalledSink(name=draining, state=Draining, queued=1)", installed.toString())

        failing.value = false
        eventually { installed.state.value == SinkState.Closed }
        assertEquals(1, sink.closes)
        assertEquals(listOf("queued"), sink.messages)
    }

    @Test
    fun `a sink added back while Draining is Active again on the same handle`() = runTest {
        val failing = atomic(true)
        val sink = Recorder(
            name = "returning",
            onFailure = SinkFailure.Retry(backoff = 5.milliseconds),
            failWith = { _, _ -> if (failing.value) refused else null },
        )
        val first = logger.addOrFail(sink)
        logger.tag("app").i { "queued" }
        eventually { sink.attempts >= 1 }
        logger.removeOrFail(sink)
        assertEquals(SinkState.Draining, first.state.value)

        val second = logger.addOrFail(sink)
        assertSame(first, second)
        assertEquals(SinkState.Active, second.state.value)

        failing.value = false
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(SinkState.Active, second.state.value)
        assertEquals(0, sink.closes)
    }

    @Test
    fun `a name is free in the same change that removes its sink`() = runTest {
        val old = Recorder("app")
        val new = Recorder("app")
        val _ = logger.addOrFail(old)

        assertEquals(Ok(Unit), logger.configure { LoggerConfig(listOf(ConfiguredSink(new, allLevels))) })
        logger.tag("app").i { "to the new one" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("to the new one"), new.messages)
        assertTrue(old.messages.isEmpty())
    }

    @Test
    fun `a removed Retry sink that keeps failing gives up after the retire timeout and counts what is left`() =
        runTest {
            val logger = testLogger(retireTimeout = 200.milliseconds)
            val stuck = Recorder(
                name = "stuck",
                onFailure = SinkFailure.Retry(backoff = 5.milliseconds),
                failWith = { _, _ -> refused },
            )
            val installed = logger.addOrFail(stuck)
            repeat(3) { logger.tag("app").i { "never written $it" } }
            eventually { stuck.attempts >= 1 }

            logger.removeOrFail(stuck)
            eventually(2.seconds) { stuck.closes == 1 }

            assertEquals(3, installed.health.value.dropped)
            assertEquals(0, installed.queued)
            val attempts = stuck.attempts
            realDelay(50.milliseconds)
            assertEquals(attempts, stuck.attempts, "a sink that gave up keeps retrying")
        }

    @Test
    fun `a removed Retry sink that keeps failing ends Closed rather than Abandoned`() = runTest {
        val logger = testLogger(retireTimeout = 100.milliseconds)
        val sink = Recorder(onFailure = SinkFailure.Retry(backoff = 5.milliseconds), failWith = { _, _ -> refused })
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "never written" }
        eventually { sink.attempts > 0 }

        logger.removeOrFail(sink)
        eventually(2.seconds) { installed.isDone }
        realDelay(50.milliseconds)

        assertEquals(SinkState.Closed, installed.state.value)
        assertEquals(1, sink.closes)
    }

    @Test
    fun `a removed sink retrying with no backoff gives up too`() = runTest {
        val logger = testLogger(retireTimeout = 100.milliseconds)
        val sink = Recorder(onFailure = SinkFailure.Retry(backoff = Duration.ZERO), failWith = { _, _ -> refused })
        val _ = logger.addOrFail(sink)
        logger.tag("app").i { "never written" }
        eventually { sink.attempts > 0 }

        logger.removeOrFail(sink)
        eventually(2.seconds) { sink.closes == 1 }

        val attempts = sink.attempts
        realDelay(50.milliseconds)
        assertEquals(attempts, sink.attempts, "the worker kept retrying after it gave up")
    }

    @Test
    fun `close keeps the sink configured and the next entry is written`() = runTest {
        val sink = Recorder("kept")
        val installed = logger.addOrFail(sink)

        val _ = installed.close()
        logger.tag("app").i { "after close" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf(installed), logger.sinks.value)
        assertEquals(listOf("after close"), sink.messages)
        assertEquals(1, sink.closes)
        assertEquals(SinkState.Active, installed.state.value)
    }

    @Test
    fun `a sink handle names itself with its state and queue`() {
        val installed = logger.addOrFail(Recorder("shown"))

        assertEquals("InstalledSink(name=shown, state=Active, queued=0)", installed.toString())
    }
}
