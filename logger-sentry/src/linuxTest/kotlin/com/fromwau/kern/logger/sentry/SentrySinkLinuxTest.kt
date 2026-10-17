package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import io.sentry.kotlin.multiplatform.Sentry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class SentrySinkLinuxTest {
    private val entry = LogEntry(Instant.parse("2026-08-11T12:34:56.789Z"), "app", LogLevel.ERROR, "failed")

    @Test
    fun `on Linux a write fails with NotEnabled even after Sentry init`() {
        Sentry.init { it.dsn = "https://key@127.0.0.1/1" }

        val written = SentrySink().write(entry, SinkConfig())
        Sentry.close()

        assertEquals(Err(SinkError.Dropped(SentryError.NotEnabled)), written)
    }

    @Test
    fun `on Linux closing the sink is Ok`() {
        assertEquals(Ok(Unit), SentrySink().close())
    }

    @Test
    fun `Sentry does not send from Linux`() {
        assertFalse(SentrySink.sendsHere)
    }
}
