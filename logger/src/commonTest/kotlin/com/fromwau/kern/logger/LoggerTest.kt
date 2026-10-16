package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.RecordingSink
import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private data class NotFound(val id: Long) : ResultError

class LoggerTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `Log starts with the platform sink`() {
        assertEquals(listOf("platform"), Log.sinks.value.map { it.sink.name })
    }

    @Test
    fun `PlatformSink is a class whose settings can be read back`() {
        val sink: LogSink = PlatformSink(name = "console", capacity = 5)

        val platform = assertIs<PlatformSink>(sink)
        assertEquals("console", platform.name)
        assertEquals(5, platform.capacity)
    }

    @Test
    fun `entries reach every sink in the order they were logged`() = runTest {
        val first = TestSink("first")
        val second = TestSink("second")
        val _ = logger.addOrFail(first)
        val _ = logger.addOrFail(second)

        repeat(200) { index -> logger.tag("app").i { "entry $index" } }
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())

        val expected = List(200) { "entry $it" }
        assertEquals(expected, first.messages)
        assertEquals(expected, second.messages)
    }

    @Test
    fun `each sink takes only entries at or above its own level`() = runTest {
        val everything = TestSink("everything")
        val warnings = TestSink("warnings")
        val _ = logger.addOrFail(everything, SinkConfig(LogLevel.VERBOSE))
        val _ = logger.addOrFail(warnings, SinkConfig(LogLevel.WARN))

        logger.tag("app").d { "debug" }
        logger.tag("app").w { "warn" }
        logger.tag("app").e { "error" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("debug", "warn", "error"), everything.messages)
        assertEquals(listOf("warn", "error"), warnings.messages)
    }

    @Test
    fun `the message is not built when no configured sink takes the level`() {
        val _ = logger.addOrFail(TestSink("warnings"), SinkConfig(LogLevel.WARN))
        var built = 0

        logger.tag("app").d {
            built++
            "expensive"
        }

        assertEquals(0, built)
    }

    @Test
    fun `the message is not built with no sink installed`() {
        var built = 0

        logger.tag("app").e {
            built++
            "nobody listens"
        }

        assertEquals(0, built)
    }

    @Test
    fun `a sink at OFF takes nothing and builds nothing`() = runTest {
        val off = TestSink("off")
        val _ = logger.addOrFail(off, SinkConfig(LogLevel.OFF))
        var built = 0

        logger.tag("app").wtf {
            built++
            "not even this"
        }
        val _ = logger.awaitFlushed()

        assertEquals(0, built)
        assertTrue(off.received.isEmpty())
    }

    @Test
    fun `an entry logged at OFF is discarded`() = runTest {
        val everything = TestSink()
        val _ = logger.addOrFail(everything)
        var built = 0

        logger.log("app", LogLevel.OFF) {
            built++
            "discarded"
        }
        val _ = logger.awaitFlushed()

        assertEquals(0, built)
        assertTrue(everything.received.isEmpty())
    }

    @Test
    fun `WTF ranks above ERROR`() = runTest {
        val errors = TestSink("errors")
        val wtfOnly = TestSink("wtf")
        val _ = logger.addOrFail(errors, SinkConfig(LogLevel.ERROR))
        val _ = logger.addOrFail(wtfOnly, SinkConfig(LogLevel.WTF))

        logger.tag("app").e { "error" }
        logger.tag("app").wtf { "impossible" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("error", "impossible"), errors.messages)
        assertEquals(listOf("impossible"), wtfOnly.messages)
        assertEquals(LogLevel.WTF, wtfOnly.received.single().level)
    }

    @Test
    fun `configure changes a sink's level from the next entry on`() = runTest {
        val sink = TestSink("sink")
        val installed = logger.addOrFail(sink, SinkConfig(LogLevel.INFO))
        var built = 0

        logger.tag("app").d { "before ${++built}" }
        logger.configureOrFail(sink) { it.copy(level = LogLevel.DEBUG) }
        logger.tag("app").d { "after ${++built}" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf("after 1"), sink.messages)
        assertEquals(LogLevel.DEBUG, installed.config.value.level)
    }

    @Test
    fun `a sink is handed its configured settings on the next write`() = runTest {
        val sink = RecordingSink(LogSink("custom") { _, _ -> Ok(Unit) })
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { "before" }
        val _ = logger.awaitFlushed()
        logger.configureOrFail(sink) { it.copy(timestamp = Timestamp.Off) }
        logger.tag("app").i { "after" }
        val _ = logger.awaitFlushed()

        assertEquals(listOf(Timestamp.On, Timestamp.Off), sink.received.map { it.config.timestamp })
    }

    @Test
    fun `raising every sink's level stops building messages`() {
        val sink = TestSink("sink")
        val _ = logger.addOrFail(sink, SinkConfig(LogLevel.DEBUG))
        logger.configureOrFail(sink) { it.copy(level = LogLevel.ERROR) }
        var built = 0

        logger.tag("app").i {
            built++
            "filtered now"
        }

        assertEquals(0, built)
    }

    @Test
    fun `an entry logged with a time keeps it`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        val then = Instant.parse("2020-01-02T03:04:05Z")

        logger.log("forwarded", LogLevel.INFO, at = then) { "from another process" }
        val _ = logger.awaitFlushed()

        assertEquals(then, sink.received.single().timestamp)
    }

    @Test
    fun `fields throwable and error reach a sink as data`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        val cause = IllegalStateException("boom")

        logger.tag("Scanner").e(cause, "count" to 412, "cached" to true, "user" to null) { "scan failed" }
        logger.tag("Scanner").w(NotFound(7), "id" to 7) { "no such user" }
        val _ = logger.awaitFlushed()

        val (thrown, typed) = sink.received
        assertEquals(mapOf("count" to 412, "cached" to true, "user" to null), thrown.fields)
        assertSame(cause, thrown.throwable)
        assertEquals(NotFound(7), typed.error)
        assertEquals(LogLevel.WARN, typed.level)
        assertEquals(mapOf("id" to 7), typed.fields)
    }

    @Test
    fun `a field key passed twice keeps its last value`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)

        logger.tag("app").i("id" to "first", "id" to "second") { "retrying" }
        val _ = logger.awaitFlushed()

        assertEquals(mapOf("id" to "second"), sink.received.single().fields)
    }

    @Test
    fun `logError logs an error result with its error and returns it unchanged`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        val failed: Result<String, NotFound> = Err(NotFound(3))

        val returned = failed.logError(logger.tag("Users"), LogLevel.WARN) { "lookup failed" }
        val _ = logger.awaitFlushed()

        assertSame(failed, returned)
        val entry = sink.received.single()
        assertEquals(NotFound(3), entry.error)
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("lookup failed", entry.message)
    }

    @Test
    fun `logError on a success logs nothing and builds nothing`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        val found: Result<String, NotFound> = Ok("ada")
        var built = 0

        val returned = found.logError(logger.tag("Users")) { "never ${++built}" }
        val _ = logger.awaitFlushed()

        assertSame(found, returned)
        assertEquals(0, built)
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun `a message block that throws is logged as a placeholder`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { error("no message today") }
        val _ = logger.awaitFlushed()

        assertEquals("<message threw IllegalStateException>", sink.messages.single())
    }

    @Test
    fun `a message block that throws an Error is logged as a placeholder`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { TODO() }
        val _ = logger.awaitFlushed()

        assertEquals("<message threw NotImplementedError>", sink.messages.single())
    }

    @Test
    fun `an entry a sink logs while writing reaches every other sink but never that one`() = runTest {
        val other = TestSink("other")
        val chatty = Recorder(
            name = "chatty",
            onWrite = { entry ->
                if (entry.message == "trigger") logger.tag("chatty").i { "logged from inside write" }
            },
        )
        // Other first, so the trigger reaches it before chatty's worker can log anything.
        val _ = logger.addOrFail(other)
        val _ = logger.addOrFail(chatty)

        logger.tag("app").i { "trigger" }
        eventually { other.messages.size == 2 }
        realDelay(50.milliseconds)
        val _ = logger.awaitFlushed()

        assertEquals(listOf("trigger", "logged from inside write"), other.messages)
        assertEquals(listOf("trigger"), chatty.messages)
    }
}
