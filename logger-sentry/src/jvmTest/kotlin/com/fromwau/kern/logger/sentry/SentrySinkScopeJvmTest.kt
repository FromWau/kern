package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import io.sentry.ScopeType
import io.sentry.SentryEvent
import io.sentry.SentryLogLevel
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.protocol.Message
import io.sentry.protocol.User
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb as KmpBreadcrumb

class SentrySinkScopeJvmTest {
    @Test
    fun `a breadcrumb a logger adds reaches an event captured on another thread`() = withSentry {
        val logger = sentryLogger(SentrySink())

        logger.tag("app").i { "signed in" }
        val flushed = logger.flush()
        Sentry.captureException(IllegalStateException("boom"))
        val _ = logger.close()

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(listOf("signed in"), events.single().breadcrumbs?.map { it.message })
    }

    @Test
    fun `closing one of two sinks keeps the trail the other wrote`() = withSentry {
        val closing = SentrySink(name = "closing")
        val remaining = SentrySink(name = "remaining")

        closing.writeAll(entry(LogLevel.INFO, "from the closing sink"))
        remaining.writeAll(entry(LogLevel.INFO, "from the remaining sink"))
        val closed = closing.close()
        Sentry.captureException(IllegalStateException("boom"))

        assertEquals(Ok(Unit), closed)
        assertEquals(
            listOf("from the closing sink", "from the remaining sink"),
            events.single().breadcrumbs?.map { it.message },
        )
    }

    @Test
    fun `a reload with a new sink keeps the trail once the old one has closed`() = withSentry {
        val oldClosed = CountDownLatch(1)
        val logger = sentryLogger(ClosingSignal(SentrySink(), oldClosed))

        logger.tag("app").i { "before the reload" }
        logger.configure { LoggerConfig(listOf(ConfiguredSink(SentrySink()))) }.assertSuccess()
        assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
        logger.tag("app").i { "after the reload" }
        val flushed = logger.flush()
        Sentry.captureException(IllegalStateException("boom"))
        val _ = logger.close()

        assertEquals(Ok(Flushed.FullyWritten), flushed)
        assertEquals(listOf("before the reload", "after the reload"), events.single().breadcrumbs?.map { it.message })
    }

    @Test
    fun `clearBreadcrumbs empties the trail`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.INFO, "signed in"))

        SentrySink.clearBreadcrumbs()
        Sentry.captureException(IllegalStateException("boom"))

        assertEquals(emptyList(), events.single().breadcrumbs.orEmpty().map { it.message })
    }

    @Test
    fun `clearBreadcrumbs keeps the breadcrumbs your app added through Sentry`() = withSentry {
        Sentry.addBreadcrumb(KmpBreadcrumb(message = "from the app"))
        SentrySink().writeAll(entry(LogLevel.INFO, "from the sink"))

        SentrySink.clearBreadcrumbs()
        Sentry.captureException(IllegalStateException("boom"))

        assertEquals(listOf("from the app"), events.single().breadcrumbs.orEmpty().map { it.message })
    }

    @Test
    fun `fields named in tags and user become the event's tags and user and the log's user`() = withSentry {
        val sink = SentrySink(tags = setOf("tenant", "count", "tag"), user = "userId")
        val fields = mapOf("tenant" to "acme", "count" to 42, "tag" to "f", "userId" to "u-7")

        sink.writeAll(entry(LogLevel.ERROR, "saving failed", tag = "Storage", fields = fields))

        val event = events.single()
        assertEquals("acme", event.getTag("tenant"))
        assertEquals("42", event.getTag("count"))
        assertEquals("Storage", event.getTag("tag"))
        assertEquals("u-7", event.user?.id)
        assertEquals(mapOf("tenant" to "acme", "count" to "42", "tag" to "f", "userId" to "u-7"), event.extras)
        assertEquals(fields, breadcrumbs.single().data.filterKeys { it in fields })
        val log = logs.single().attributes.orEmpty().mapValues { it.value.value }
        assertEquals(
            mapOf("tenant" to "acme", "count" to 42L, "tag" to "Storage", "userId" to "u-7"),
            log.filterKeys { it in fields },
        )
        assertEquals("u-7", log["user.id"])
    }

    @Test
    fun `a field named in tags or user whose value is null leaves them out`() = withSentry {
        val sink = SentrySink(tags = setOf("tenant"), user = "userId")

        sink.writeAll(entry(LogLevel.ERROR, "saving failed", fields = mapOf("tenant" to null, "userId" to null)))

        val event = events.single()
        assertNull(event.getTag("tenant"))
        assertNull(event.user?.id)
    }

    @Test
    fun `a non-string user field is sent as its text`() = withSentry {
        SentrySink(user = "userId").writeAll(entry(LogLevel.ERROR, "saving failed", fields = mapOf("userId" to 42L)))

        assertEquals("42", events.single().user?.id)
    }

    @Test
    fun `a user field longer than any budget names the event's and the log's user in full`() = withSentry {
        val user = "u".repeat(20_000)
        val fields = mapOf("userId" to user) + (0..<300).associate { "f$it" to "v".repeat(50) }

        SentrySink(user = "userId").writeAll(entry(LogLevel.ERROR, "saving failed", fields = fields))

        assertEquals(user, events.single().user?.id)
        assertEquals(user, logs.single().attributes.orEmpty()["user.id"]?.value)
    }

    @Test
    fun `a user field the event's budget would leave out still names the log's user`() = withSentry {
        // The costliest field, so an event of this entry leaves it out first.
        val user = "u".repeat(200)
        val fields = (0..<300).associate { "f$it" to "v".repeat(50) } + ("userId" to user)

        SentrySink(user = "userId", events = LogLevel.OFF).writeAll(entry(LogLevel.ERROR, "saved", fields = fields))

        assertEquals(user, logs.single().attributes.orEmpty()["user.id"]?.value)
    }

    @Test
    fun `the user field names the user of the event and the log over the global scope's user`() = withSentry {
        val logger = sentryLogger(SentrySink(user = "userId"))
        io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { it.user = User().apply { id = "global-user" } }

        logger.tag("app").e("userId" to "u2") { "checkout failed" }
        logger.tag("app").e { "no user field" }
        val _ = logger.close()

        assertEquals(listOf("u2", "global-user"), events.map { it.user?.id })
        assertEquals(listOf("u2", "global-user"), logs.map { it.attributes?.get("user.id")?.value })
    }

    @Test
    fun `scopes forked from sentry-java's root scopes capture events and logs`() = withSentry {
        val scopes = io.sentry.Sentry.forkedRootScopes("kern")

        scopes.captureEvent(SentryEvent().apply { message = Message().apply { formatted = "forked" } })
        scopes.logger().log(SentryLogLevel.INFO, "forked")

        assertEquals(listOf("forked"), events.map { it.message?.formatted })
        assertEquals(listOf("forked"), logs.map { it.body })
    }

    @Test
    fun `events and logs still go out without forkedRootScopes`() = withSentry {
        val stderr = withoutForkedRootScopes {
            val logger = sentryLogger(SentrySink())
            logger.tag("app").e("order" to "42") { "checkout failed" }
            val _ = logger.close()
        }

        val event = events.single()
        assertEquals("checkout failed", event.message?.formatted)
        assertEquals("42", event.getExtra("order"))
        assertEquals(listOf("app", "checkout failed"), event.fingerprints)
        assertEquals(listOf("checkout failed"), logs.map { it.body })
        assertEquals("42", logs.single().attributes?.get("order")?.value)
        assertEquals(listOf(FALLBACK_LINE), stderr.lines)
    }

    @Test
    fun `without forkedRootScopes the sink says so once and stops trying the fork`() = withSentry {
        val stderr = withoutForkedRootScopes {
            val logger = sentryLogger(SentrySink())
            repeat(20) { logger.tag("app").e { "failed $it" } }
            val _ = logger.close()
            SentrySink().writeAll(entry(LogLevel.ERROR, "later"))
        }

        assertEquals(21, events.size)
        assertEquals(21, logs.size)
        assertEquals(listOf(FALLBACK_LINE), stderr.lines)
        assertEquals(1, stderr.forkCalls)
    }

    private class FallbackRun(val lines: List<String>, val forkCalls: Int)

    /**
     * Runs [block] with a fork that fails to link, as on a sentry-java without it, recording standard error. The fork
     * stays broken until [resetSentryGlobals].
     */
    private fun withoutForkedRootScopes(block: () -> Unit): FallbackRun {
        val forkCalls = AtomicInteger()
        val err = ByteArrayOutputStream()
        val systemErr = System.err
        forkRootScopes.set {
            forkCalls.incrementAndGet()
            throw NoSuchMethodError("'io.sentry.IScopes io.sentry.Sentry.forkedRootScopes(java.lang.String)'")
        }
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(systemErr)
        }
        val lines = err.toString(Charsets.UTF_8).lines().filter { it.startsWith("kern.logger.sentry") }
        return FallbackRun(lines, forkCalls.get())
    }

    private class ClosingSignal(private val sink: SentrySink, private val closed: CountDownLatch) : LogSink by sink {
        override fun close() = sink.close().also { closed.countDown() }
    }
}

private const val FALLBACK_LINE =
    "kern.logger.sentry: this sentry-java has no Sentry.forkedRootScopes (NoSuchMethodError); " +
        "events and logs now use the current thread's scope"
