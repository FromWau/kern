package com.fromwau.kern.logger

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class RenderingTest {

    private val entry = LogEntry(
        timestamp = Instant.parse("2026-08-11T12:34:56.789Z"),
        tag = "Scanner",
        level = LogLevel.INFO,
        message = "scan complete",
        fields = mapOf("count" to "412"),
    )

    @Test
    fun `a text line carries the level tag message and fields`() {
        val line = entry.toTextLine()

        assertContains(line, "INFO")
        assertContains(line, "Scanner")
        assertContains(line, "scan complete")
        assertContains(line, "count=412")
    }

    @Test
    fun `a json line nests the fields under their own key`() {
        val line = entry.toJsonLine()

        assertContains(line, "\"fields\":{\"count\":\"412\"}")
        assertContains(line, "\"level\":\"INFO\"")
        assertContains(line, "\"timestamp\":\"2026-08-11T12:34:56.789Z\"")
    }

    @Test
    fun `a json line stays one line even when the message spans several`() {
        val line = entry.copy(message = "he said \"hi\"\nthen left").toJsonLine()

        assertFalse(line.contains('\n'), "a shipper reads one object per line: $line")
        assertContains(line, "\\\"hi\\\"")
        assertContains(line, "\\n")
    }

    @Test
    fun `an entry without fields or a throwable omits both keys`() {
        val line = entry.copy(fields = emptyMap()).toJsonLine()

        assertFalse(line.contains("fields"))
        assertFalse(line.contains("stackTrace"))
    }

    @Test
    fun `a throwable is rendered under the message rather than folded into it`() {
        val withCause = entry.copy(throwable = IllegalStateException("boom"))

        assertContains(withCause.toTextLine(), "scan complete")
        assertContains(withCause.toTextLine(), "boom")
        assertContains(withCause.toJsonLine(), "\"stackTrace\"")
        assertEquals("scan complete", withCause.message)
    }
}
