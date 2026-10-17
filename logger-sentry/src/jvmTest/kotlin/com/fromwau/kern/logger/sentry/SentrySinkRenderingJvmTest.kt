package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.ResultError
import io.sentry.Hint
import io.sentry.SentryEnvelope
import io.sentry.SentryItemType
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SentrySinkRenderingJvmTest {
    @Test
    fun `each destination takes the entries at or above its own level`() = withSentry {
        val sink = SentrySink(events = LogLevel.WARN, breadcrumbs = LogLevel.DEBUG, logs = LogLevel.INFO)

        sink.writeAll(*everyLevel())

        assertEquals(listOf("WARN", "ERROR", "WTF"), events.map { it.message?.formatted })
        assertEquals(listOf("DEBUG", "INFO", "WARN", "ERROR", "WTF"), breadcrumbs.map { it.message })
        assertEquals(listOf("INFO", "WARN", "ERROR", "WTF"), logs.map { it.body })
    }

    @Test
    fun `the defaults send errors as events and info and above as breadcrumbs and logs`() = withSentry {
        SentrySink().writeAll(*everyLevel())

        assertEquals(listOf("ERROR", "WTF"), events.map { it.message?.formatted })
        assertEquals(listOf("INFO", "WARN", "ERROR", "WTF"), breadcrumbs.map { it.message })
        assertEquals(listOf("INFO", "WARN", "ERROR", "WTF"), logs.map { it.body })
    }

    @Test
    fun `each level maps onto Sentry's levels`() = withSentry {
        val sink = SentrySink(events = LogLevel.VERBOSE, breadcrumbs = LogLevel.VERBOSE, logs = LogLevel.VERBOSE)

        sink.writeAll(*everyLevel())

        val levels = listOf(
            SentryLevel.DEBUG,
            SentryLevel.DEBUG,
            SentryLevel.INFO,
            SentryLevel.WARNING,
            SentryLevel.ERROR,
            SentryLevel.FATAL,
        )
        assertEquals(levels, events.map { it.level })
        assertEquals(levels, breadcrumbs.map { it.level })
        assertEquals(
            listOf(
                SentryLogLevel.TRACE,
                SentryLogLevel.DEBUG,
                SentryLogLevel.INFO,
                SentryLogLevel.WARN,
                SentryLogLevel.ERROR,
                SentryLogLevel.FATAL,
            ),
            logs.map { it.level },
        )
    }

    @Test
    fun `logs OFF sends no logs`() = withSentry {
        SentrySink(logs = LogLevel.OFF).writeAll(*everyLevel())

        assertTrue(logs.isEmpty())
        assertEquals(4, breadcrumbs.size)
    }

    @Test
    fun `OFF switches every destination off`() = withSentry {
        SentrySink(events = LogLevel.OFF, breadcrumbs = LogLevel.OFF, logs = LogLevel.OFF).writeAll(*everyLevel())

        assertTrue(events.isEmpty() && breadcrumbs.isEmpty() && logs.isEmpty())
    }

    @Test
    fun `an entry below every level is written without sending anything`() = withSentry {
        val written = SentrySink().write(entry(LogLevel.DEBUG, "quiet"), SinkConfig(level = LogLevel.VERBOSE))

        assertEquals(Ok(Unit), written)
        assertTrue(events.isEmpty() && breadcrumbs.isEmpty() && logs.isEmpty())
    }

    @Test
    fun `an entry with a throwable is captured as that exception with its message as an extra`() = withSentry {
        val failure = IllegalStateException("disk gone")

        SentrySink().writeAll(entry(LogLevel.ERROR, "saving failed", throwable = failure))

        val event = events.single()
        assertEquals(failure, event.throwable)
        assertEquals("saving failed", event.getExtra("message"))
        assertNull(event.message)
    }

    @Test
    fun `an entry without a throwable is captured as its message`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "saving failed"))

        val event = events.single()
        assertEquals("saving failed", event.message?.formatted)
        assertNull(event.throwable)
        assertNull(event.getExtra("message"))
    }

    @Test
    fun `an event with a throwable carries the exception Sentry builds for captureException`() = withSentry {
        // Two of the same shape, since Sentry drops a throwable it has captured before.
        val (forSink, forSentry) = List(2) {
            IllegalStateException("disk gone", IllegalArgumentException("bad sector"))
        }

        SentrySink().writeAll(entry(LogLevel.ERROR, "saving failed", throwable = forSink))
        Sentry.captureException(forSentry)

        val (fromSink, fromSentry) = events.map { event ->
            event.exceptions.orEmpty().map { exception ->
                listOf(
                    exception.type,
                    exception.value,
                    exception.module,
                    exception.mechanism?.type,
                    exception.stacktrace?.frames?.map { "${it.module}.${it.function}:${it.lineno}" },
                )
            }
        }
        assertEquals(2, fromSink.size)
        assertEquals(fromSentry, fromSink)
    }

    @Test
    fun `a log carries its throwable's type and message`() = withSentry {
        SentrySink().writeAll(
            entry(LogLevel.ERROR, "saving failed", throwable = IllegalStateException("disk gone")),
            entry(LogLevel.ERROR, "saving failed", throwable = IllegalStateException()),
        )

        val (withMessage, withoutMessage) = logs.map { it.attributes.orEmpty() }
        assertEquals("IllegalStateException", withMessage["exception.type"]?.value)
        assertEquals("disk gone", withMessage["exception.message"]?.value)
        assertFalse("exception" in withMessage)
        assertEquals("IllegalStateException", withoutMessage["exception.type"]?.value)
        assertFalse("exception.message" in withoutMessage)
    }

    @Test
    fun `a throwable whose message throws still reaches all three`() = withSentry {
        val logger = sentryLogger(SentrySink())

        logger.tag("app").e(MessageThrows()) { "saving failed" }
        val flushed = logger.flush()
        val health = logger.sinks.value.single().health.value
        val _ = logger.close()

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(0, health.failures)
        assertIs<MessageThrows>(events.single().throwable)
        assertEquals("saving failed", breadcrumbs.single().message)
        val log = logs.single().attributes.orEmpty()
        assertEquals("MessageThrows", log["exception.type"]?.value)
        assertEquals("<message threw IllegalStateException>", log["exception.message"]?.value)
    }

    @Test
    fun `the message reaches Sentry exactly as logged`() = withSentry {
        val message = "50% done, 100%% sure, %s and %d left"

        SentrySink().writeAll(entry(LogLevel.ERROR, message))

        assertEquals(message, events.single().message?.formatted)
        assertEquals(message, breadcrumbs.single().message)
        assertEquals(message, logs.single().body)
    }

    @Test
    fun `the tag reaches all three`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "saved", tag = "Storage"))

        assertEquals("Storage", events.single().getTag("tag"))
        assertEquals("Storage", breadcrumbs.single().category)
        assertEquals("string" to "Storage", logs.single().attributes?.get("tag")?.let { it.type to it.value })
    }

    @Test
    fun `the entry's ResultError and its type reach all three`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "saving failed", error = DiskFull(3)))

        assertEquals("DiskFull(free=3)", events.single().getExtra("error"))
        assertEquals("DiskFull(free=3)", breadcrumbs.single().getData("error"))
        assertEquals("DiskFull(free=3)", logs.single().attributes?.get("error")?.value)
        assertEquals("DiskFull", events.single().getTag("error.type"))
        assertEquals("DiskFull", breadcrumbs.single().getData("error.type"))
        assertEquals("DiskFull", logs.single().attributes?.get("error.type")?.value)
    }

    @Test
    fun `an error's own errorType names it on all three and groups its event`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "card declined", tag = "Checkout", error = Declined("51")))

        assertEquals("Payment.Declined", events.single().getTag("error.type"))
        assertEquals(listOf("Checkout", "Payment.Declined"), events.single().fingerprints)
        assertEquals("Payment.Declined", breadcrumbs.single().getData("error.type"))
        assertEquals("Payment.Declined", logs.single().attributes?.get("error.type")?.value)
    }

    @Test
    fun `an error whose errorType throws still reaches all three as its class's simple name`() = withSentry {
        val logger = sentryLogger(SentrySink())

        logger.tag("Storage").e(TypeThrows) { "saving failed" }
        val flushed = logger.flush()
        val health = logger.sinks.value.single().health.value
        val _ = logger.close()

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(0, health.dropped)
        val event = events.single()
        assertEquals("TypeThrows", event.getTag("error.type"))
        assertEquals(listOf("Storage", "TypeThrows"), event.fingerprints)
        assertEquals("TypeThrows", breadcrumbs.single().getData("error.type"))
        assertEquals("TypeThrows", logs.single().attributes?.get("error.type")?.value)
    }

    @Test
    fun `an event groups by tag and error type, by tag and message, or by its throwable`() = withSentry {
        SentrySink().writeAll(
            entry(LogLevel.ERROR, "saving failed", tag = "Storage", error = DiskFull(3)),
            entry(LogLevel.ERROR, "saving failed", tag = "Storage", error = object : ResultError {}),
            entry(LogLevel.ERROR, "saving failed", tag = "Storage"),
            entry(LogLevel.ERROR, "saving failed", tag = "Storage", throwable = IllegalStateException("disk gone")),
        )

        assertEquals(
            listOf(
                listOf("Storage", "DiskFull"),
                listOf("Storage", "ResultError"),
                listOf("Storage", "saving failed"),
            ),
            events.take(3).map { it.fingerprints },
        )
        assertTrue(events[3].fingerprints.isNullOrEmpty())
    }

    @Test
    fun `a field gives way only where the sink sets the same key`() = withSentry {
        val fields = mapOf("tag" to "f", "error" to "f", "error.type" to "f", "message" to "f")

        SentrySink().writeAll(
            entry(LogLevel.ERROR, "saving failed", error = DiskFull(3), fields = fields),
            entry(
                LogLevel.ERROR,
                "crashed",
                throwable = IllegalStateException("boom"),
                fields = mapOf("exception.type" to "f", "exception.message" to "f", "message" to "f"),
            ),
        )

        val (typed, thrown) = events
        assertEquals("DiskFull(free=3)", typed.getExtra("error"))
        assertEquals("DiskFull", typed.getTag("error.type"))
        assertEquals("f", typed.getExtra("tag"))
        assertEquals("f", typed.getExtra("message"))
        assertEquals("crashed", thrown.getExtra("message"))
        val log = logs.first().attributes.orEmpty()
        assertEquals("app", log["tag"]?.value)
        assertEquals("DiskFull", log["error.type"]?.value)
        assertEquals("DiskFull(free=3)", log["error"]?.value)
        val thrownLog = logs.last().attributes.orEmpty()
        assertEquals("IllegalStateException", thrownLog["exception.type"]?.value)
        assertEquals("boom", thrownLog["exception.message"]?.value)
        assertEquals("DiskFull", breadcrumbs.first().getData("error.type"))
        assertEquals("f", breadcrumbs.first().getData("tag"))
    }

    @Test
    fun `fields are typed in logs and breadcrumbs and text in events with null ones left out`() = withSentry {
        val fields = mapOf(
            "s" to "text",
            "i" to 1,
            "l" to 2L,
            "f" to 1.5f,
            "d" to 2.5,
            "b" to true,
            "o" to Point(1, 2),
            "n" to null,
        )

        SentrySink().writeAll(entry(LogLevel.ERROR, "saved", fields = fields))

        val attributes = logs.single().attributes.orEmpty()
        assertEquals(
            mapOf(
                "s" to ("string" to "text"),
                "i" to ("integer" to 1L),
                "l" to ("integer" to 2L),
                "f" to ("double" to 1.5),
                "d" to ("double" to 2.5),
                "b" to ("boolean" to true),
                "o" to ("string" to "Point(x=1, y=2)"),
            ),
            attributes
                .filterKeys { it in fields }
                .mapValues { (_, value) -> value.type to value.value },
        )
        assertFalse("n" in attributes)

        val data = breadcrumbs.single().data
        assertEquals(
            mapOf("s" to "text", "i" to 1, "l" to 2L, "f" to 1.5f, "d" to 2.5, "b" to true, "o" to "Point(x=1, y=2)"),
            data.filterKeys { it in fields },
        )
        assertFalse("n" in data)

        val extras = events.single().extras.orEmpty()
        assertEquals(
            mapOf(
                "s" to "text",
                "i" to "1",
                "l" to "2",
                "f" to "1.5",
                "d" to "2.5",
                "b" to "true",
                "o" to "Point(x=1, y=2)",
            ),
            extras.filterKeys { it in fields },
        )
        assertFalse("n" in extras)
    }

    @Test
    fun `Short and Byte reach logs and breadcrumbs as numbers and a Number of its own as text`() = withSentry {
        val fields = mapOf("short" to 3.toShort(), "byte" to 4.toByte(), "ratio" to Ratio(1, 3))

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        assertEquals(
            mapOf("short" to ("integer" to 3L), "byte" to ("integer" to 4L), "ratio" to ("string" to "1/3")),
            logs.single()
                .attributes
                .orEmpty()
                .filterKeys { it in fields }
                .mapValues { it.value.type to it.value.value },
        )
        assertEquals(
            mapOf("short" to 3.toShort(), "byte" to 4.toByte(), "ratio" to "1/3"),
            breadcrumbs.single().data.filterKeys { it in fields },
        )
        assertEquals(
            mapOf("short" to "3", "byte" to "4", "ratio" to "1/3"),
            events.single().extras.orEmpty().filterKeys { it in fields },
        )
    }

    @Test
    fun `array fields reach all three as their contents`() = withSentry {
        val fields = mapOf(
            "doubles" to doubleArrayOf(1.5, 2.0),
            "ints" to intArrayOf(1, 2),
            "strings" to arrayOf("a", "b"),
        )

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        val text = mapOf("doubles" to "[1.5, 2.0]", "ints" to "[1, 2]", "strings" to "[a, b]")
        assertEquals(text, events.single().extras.orEmpty().filterKeys { it in fields })
        assertEquals(text, breadcrumbs.single().data.filterKeys { it in fields })
        assertEquals(
            text.mapValues { (_, value) -> "string" to value },
            logs.single().attributes.orEmpty().filterKeys { it in fields }.mapValues { (_, it) -> it.type to it.value },
        )
    }

    @Test
    fun `arrays and collections nested in a field reach all three as their contents`() = withSentry {
        val fields = mapOf(
            "arrays" to arrayOf<Any>(intArrayOf(1), arrayOf("x")),
            "list" to listOf(intArrayOf(1)),
            "self" to mutableListOf<Any>().apply { add(this) },
            "arrayOfList" to arrayOf<Any>(listOf(intArrayOf(1))),
            "cycle" to arrayOfNulls<Any>(1).apply { set(0, listOf(this)) },
        )

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        val text = mapOf(
            "arrays" to "[[1], [x]]",
            "list" to "[[1]]",
            "self" to "[(this Collection)]",
            "arrayOfList" to "[[[1]]]",
            "cycle" to "[[[...]]]",
        )
        assertEquals(text, events.single().extras.orEmpty().filterKeys { it in fields })
        assertEquals(text, breadcrumbs.single().data.filterKeys { it in fields })
        assertEquals(text, logs.single().attributes.orEmpty().filterKeys { it in fields }.mapValues { it.value.value })
    }

    @Test
    fun `a throwing toString on the error or a field still reaches all three as kern's placeholder`() = withSentry {
        val logger = sentryLogger(SentrySink())

        logger.tag("app").e(ToStringThrows, "value" to ToStringThrowsValue) { "saving failed" }
        val flushed = logger.flush()
        val health = logger.sinks.value.single().health.value
        val _ = logger.close()

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(0, health.dropped)
        val placeholder = "<toString() threw IllegalStateException>"
        val text = mapOf("error" to placeholder, "value" to placeholder)
        assertEquals(text, events.single().extras.orEmpty().filterKeys { it in text })
        assertEquals(text, breadcrumbs.single().data.filterKeys { it in text })
        assertEquals(text, logs.single().attributes.orEmpty().filterKeys { it in text }.mapValues { it.value.value })
        assertEquals("ToStringThrows", events.single().getTag("error.type"))
    }

    @Test
    fun `a field whose iteration or whose Number toString throws reaches all three as kern's placeholder`() =
        withSentry {
            val logger = sentryLogger(SentrySink())

            logger.tag("app").e(
                "iterator" to IteratorThrows(IllegalStateException()),
                "modified" to IteratorThrows(ConcurrentModificationException()),
                "number" to NumberToStringThrows,
            ) { "saving failed" }
            val flushed = logger.flush()
            val health = logger.sinks.value.single().health.value
            val _ = logger.close()

            assertEquals(Ok(Flushed.FullyWritten), flushed)
            assertEquals(0, health.failures)
            val text = mapOf(
                "iterator" to "<toString() threw IllegalStateException>",
                "modified" to "<toString() threw ConcurrentModificationException>",
                "number" to "<toString() threw IllegalStateException>",
            )
            assertEquals(text, events.single().extras.orEmpty().filterKeys { it in text })
            assertEquals(text, breadcrumbs.single().data.filterKeys { it in text })
            assertEquals(
                text,
                logs.single().attributes.orEmpty().filterKeys { it in text }.mapValues { it.value.value },
            )
        }

    @Test
    fun `non-finite numbers reach logs and breadcrumbs as text`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = nonFinite))

        val text = mapOf("nan" to "NaN", "inf" to "Infinity", "negInf" to "-Infinity")
        assertEquals(
            text.mapValues { (_, value) -> "string" to value },
            logs.single().attributes.orEmpty().filterKeys { it in text }.mapValues { (_, it) -> it.type to it.value },
        )
        assertEquals(text, breadcrumbs.single().data.filterKeys { it in text })
    }

    @Test
    fun `non-finite numbers do not stop Sentry from serializing what it sends`() {
        val envelopes = CopyOnWriteArrayList<SentryEnvelope>()
        withSentryOptions(
            { options ->
                options.setTransportFactory { _, _ -> RecordingTransport(envelopes) }
                // Close otherwise waits out the default 2 s for the log batch it has scheduled, then sends it anyway.
                options.shutdownTimeoutMillis = 50
            },
        ) {
            SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = nonFinite))
            // The breadcrumb goes out with the next event.
            Sentry.captureMessage("next")
        }

        val items = envelopes.flatMap { it.items }
        assertTrue(items.any { it.header.type == SentryItemType.Event })
        assertTrue(items.any { it.header.type == SentryItemType.Log })
        items.forEach { item -> val _ = item.data }
    }

    @Test
    fun `an entry reaches all three with the time it was logged`() = withSentry {
        val loggedAt = Clock.System.now() - 5.seconds

        SentrySink().writeAll(
            entry(LogLevel.ERROR, "saving failed", timestamp = loggedAt),
            entry(LogLevel.ERROR, "crashed", throwable = IllegalStateException("boom"), timestamp = loggedAt),
        )

        val millis = loggedAt.toEpochMilliseconds()
        assertEquals(listOf(millis, millis), events.map { it.timestamp.time })
        assertEquals(listOf(millis, millis), breadcrumbs.map { it.timestamp.time })
        logs.forEach { log -> assertSecondsAt(loggedAt, log.timestamp) }
    }

    @Test
    fun `an entry logged through a logger reaches all three with the time it was logged`() = withSentry {
        val loggedAt = Clock.System.now() - 5.seconds
        val logger = sentryLogger(SentrySink())

        logger.log("app", LogLevel.ERROR, at = loggedAt) { "saving failed" }
        val _ = logger.close()

        val millis = loggedAt.toEpochMilliseconds()
        assertEquals(millis, events.single().timestamp.time)
        assertEquals(millis, breadcrumbs.single().timestamp.time)
        assertSecondsAt(loggedAt, logs.single().timestamp)
    }

    @Test
    fun `a promoted value over 200 characters becomes a 200-character tag ending in an ellipsis`() = withSentry {
        val value = "v".repeat(300)

        SentrySink(tags = setOf("query")).writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("query" to value)))

        val event = events.single()
        assertEquals("v".repeat(199) + "…", event.getTag("query"))
        assertEquals(value, event.getExtra("query"))
    }

    @Test
    fun `a promoted value of 200 characters is its tag as it is, newlines included`() = withSentry {
        val value = "a\n".repeat(100)

        SentrySink(tags = setOf("query")).writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("query" to value)))

        assertEquals(value, events.single().getTag("query"))
    }

    @Test
    fun `a promoted value is cut to a tag without splitting an emoji`() = withSentry {
        val value = "a".repeat(198) + "😀".repeat(5)

        SentrySink(tags = setOf("query")).writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("query" to value)))

        assertEquals("a".repeat(198) + "…", events.single().getTag("query"))
    }

    private data class Point(val x: Int, val y: Int)

    private data class Declined(val code: String) : ResultError {
        override val errorType: String get() = "Payment.Declined"
    }

    private data object TypeThrows : ResultError {
        override val errorType: String get() = error("no type")
    }

    private object ToStringThrows : ResultError {
        override fun toString(): String = error("no text")
    }

    private object ToStringThrowsValue {
        override fun toString(): String = error("no text")
    }

    /** A collection whose iteration fails with [failure], as one changed while it is read does. */
    private class IteratorThrows(private val failure: Throwable) : AbstractList<Int>() {
        override val size: Int = 1

        override fun get(index: Int): Int = 1

        override fun iterator(): Iterator<Int> = throw failure
    }

    private object NumberToStringThrows : Number() {
        override fun toDouble(): Double = 1.0

        override fun toFloat(): Float = 1f

        override fun toLong(): Long = 1

        override fun toInt(): Int = 1

        override fun toShort(): Short = 1

        override fun toByte(): Byte = 1

        override fun toString(): String = error("no text")
    }

    /** A [Number] of its own, written as its `toString()`. */
    private class Ratio(private val over: Int, private val under: Int) : Number() {
        override fun toDouble(): Double = over.toDouble() / under

        override fun toFloat(): Float = toDouble().toFloat()

        override fun toLong(): Long = toDouble().toLong()

        override fun toInt(): Int = toDouble().toInt()

        override fun toShort(): Short = toInt().toShort()

        override fun toByte(): Byte = toInt().toByte()

        override fun toString(): String = "$over/$under"
    }

    private class MessageThrows : Exception() {
        override val message: String
            get() = error("no message")
    }

    private class RecordingTransport(private val envelopes: MutableList<SentryEnvelope>) : ITransport {
        override fun send(envelope: SentryEnvelope, hint: Hint) {
            envelopes += envelope
        }

        override fun flush(timeoutMillis: Long) = Unit

        override fun getRateLimiter(): RateLimiter? = null

        override fun close(isRestarting: Boolean) = Unit

        override fun close() = Unit
    }
}

private val nonFinite: Map<String, Any?> = mapOf(
    "nan" to Double.NaN,
    "inf" to Float.POSITIVE_INFINITY,
    "negInf" to Double.NEGATIVE_INFINITY,
)

/** Sentry Logs stamp a log in seconds as a `Double`, so it is compared to the millisecond. */
private fun assertSecondsAt(
    expected: Instant,
    seconds: Double,
) {
    val expectedSeconds = expected.epochSeconds + expected.nanosecondsOfSecond / 1e9
    assertTrue(abs(seconds - expectedSeconds) < 1e-3, "$seconds is not $expectedSeconds")
}
