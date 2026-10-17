package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import io.sentry.kotlin.multiplatform.Sentry
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Sends to a real Sentry project, under the release kern-logger-sentry-smoke. Runs only when SENTRY_DSN is set. */
class LiveSentryTest {
    @Test
    fun `entries reach a real Sentry project`() {
        val dsn = LiveSentryConfig.dsn
        assumeTrue(dsn != null, "SENTRY_DSN is not set")

        Sentry.init { options ->
            options.dsn = dsn
            options.logs.enabled = true
            options.release = "kern-logger-sentry-smoke"
        }
        try {
            val logger = Logger()
            logger.configure { LoggerConfig(listOf(ConfiguredSink(SentrySink()))) }.assertSuccess()
            val log = logger.tag("smoke")

            log.i("step" to 1, "user" to "ada") { "smoke test started" }
            log.i("step" to 2, "ratio" to 0.5) { "smoke test halfway" }
            log.e(IllegalStateException("smoke test failure"), "step" to 3) { "smoke test failed on purpose" }

            assertEquals(Ok(Unit), logger.close(5.seconds))
        } finally {
            Sentry.close()
        }
    }
}
