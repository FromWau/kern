package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.FlushError
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SentrySinkLifecycleJvmTest {
    @Test
    fun `Sentry sends from the JVM`() {
        assertTrue(SentrySink.sendsHere)
    }

    @Test
    fun `while Sentry is not enabled a write fails with NotEnabled`() {
        val beforeInit = SentrySink().write(entry(LogLevel.ERROR, "early"), SinkConfig())
        withSentry { }
        val afterClose = SentrySink().write(entry(LogLevel.ERROR, "late"), SinkConfig())

        assertEquals(Err(SinkError.Dropped(SentryError.NotEnabled)), beforeInit)
        assertEquals(Err(SinkError.Dropped(SentryError.NotEnabled)), afterClose)
    }

    @Test
    fun `an entry below every level is Ok while Sentry is off`() {
        val written = SentrySink().write(entry(LogLevel.DEBUG, "quiet"), SinkConfig(level = LogLevel.VERBOSE))

        assertEquals(Ok(Unit), written)
    }

    @Test
    fun `a logger counts what Sentry was not enabled for as dropped`() {
        val sink = SentrySink()
        val logger = sentryLogger(sink)

        logger.tag("app").e { "early" }
        val flushed = logger.flush()
        val installed = logger.sinks.value.single()
        val _ = logger.close()

        assertEquals(Err(FlushError.Lost(mapOf("sentry" to 1L), emptyMap())), flushed)
        assertEquals(1, installed.health.value.dropped)
        assertEquals(SinkError.Dropped(SentryError.NotEnabled), installed.health.value.lastError)
    }

    @Test
    fun `the first entry after Sentry init goes out`() {
        val logger = sentryLogger(SentrySink())
        logger.tag("app").e { "early" }
        val _ = logger.flush()

        withSentry {
            logger.tag("app").e { "after init" }
            val _ = logger.close()

            assertEquals(listOf("after init"), events.map { it.message?.formatted })
        }
    }

    @Test
    fun `a logger delivers its entries to Sentry`() = withSentry {
        val logger = sentryLogger(SentrySink())

        logger.tag("app").i("user" to "ada") { "signed in" }
        logger.tag("app").e(IllegalStateException("boom")) { "crashed" }
        val closed = logger.close()

        assertEquals(Ok(Unit), closed)
        assertEquals(listOf("signed in", "crashed"), breadcrumbs.map { it.message })
        assertEquals(listOf("signed in", "crashed"), logs.map { it.body })
        val event = events.single()
        assertIs<IllegalStateException>(event.throwable)
        assertEquals(listOf("signed in"), event.breadcrumbs?.map { it.message })
    }
}
