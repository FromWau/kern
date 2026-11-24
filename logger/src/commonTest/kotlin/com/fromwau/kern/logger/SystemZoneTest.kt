package com.fromwau.kern.logger

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class SystemZoneTest {
    private val summer = Instant.parse("2026-07-01T12:00:00Z")
    private val reports = mutableListOf<String>()
    private val note = OnceNote { reports += it }

    @Test
    fun `the system zone is used when it can be read`() {
        val clock = kotlinxSystemClock({ UtcOffset(hours = 9).asTimeZone() }, note)

        assertEquals(UtcOffset(hours = 9), clock.offsetAt(summer))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `a zone with no known rules falls back to UTC with a note naming the cause`() {
        val unknown = IllegalTimeZoneException("no rules for Mars/Base")

        val clock = kotlinxSystemClock({ throw unknown }, note)

        assertEquals(UtcOffset.ZERO, clock.offsetAt(summer))
        assertContains(reports.single(), "Mars/Base")
        assertContains(reports.single(), "UTC")
    }

    @Test
    fun `the fallback note is written once however many clocks fall back`() {
        repeat(3) { val _ = kotlinxSystemClock({ throw IllegalTimeZoneException("no rules") }, note) }

        assertEquals(1, reports.size)
    }

    @Test
    fun `any other throw reading the zone is not caught`() {
        val thrown = IllegalArgumentException("a bug")

        val caught = assertFailsWith<IllegalArgumentException> {
            kotlinxSystemClock({ throw thrown }, note)
        }

        assertSame(thrown, caught)
        assertTrue(reports.isEmpty())
    }
}
