package com.fromwau.kern.logger

import kotlinx.datetime.UtcOffset
import java.time.DateTimeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Instant

class SystemZoneJvmTest {
    @Test
    fun `a system zone the JVM cannot read falls back to UTC with a note`() {
        val reports = mutableListOf<String>()
        val unreadable = DateTimeException("Invalid ID for region-based ZoneId")

        val clock = kotlinxSystemClock({ throw unreadable }, OnceNote { reports += it })

        assertEquals(UtcOffset.ZERO, clock.offsetAt(Instant.parse("2026-07-01T12:00:00Z")))
        assertContains(reports.single(), "Invalid ID")
    }
}
