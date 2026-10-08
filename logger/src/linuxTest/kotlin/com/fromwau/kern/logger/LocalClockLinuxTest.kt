@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.datetime.UtcOffset
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LocalClockLinuxTest {
    private val summer = Instant.parse("2026-08-11T12:00:00Z")
    private val winter = Instant.parse("2026-01-11T12:00:00Z")

    private fun withTz(tz: String, block: () -> Unit) {
        val previous = getenv("TZ")?.toKString()
        setenv("TZ", tz, 1)
        try {
            block()
        } finally {
            if (previous == null) unsetenv("TZ") else setenv("TZ", previous, 1)
        }
    }

    @Test
    fun `a TZ rule with daylight saving is followed as the C library reads it`() {
        withTz("CET-1CEST,M3.5.0,M10.5.0/3") {
            val clock = systemLocalClock()

            assertEquals(UtcOffset(hours = 2), clock.offsetAt(summer))
            assertEquals(UtcOffset(hours = 1), clock.offsetAt(winter))
        }
    }

    @Test
    fun `UTC0 and a zone name are read too`() {
        withTz("UTC0") { assertEquals(UtcOffset.ZERO, systemLocalClock().offsetAt(summer)) }
        withTz("Asia/Tokyo") { assertEquals(UtcOffset(hours = 9), systemLocalClock().offsetAt(summer)) }
    }
}
