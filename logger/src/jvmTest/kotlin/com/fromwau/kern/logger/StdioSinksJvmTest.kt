package com.fromwau.kern.logger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StdioSinksJvmTest {
    @Test
    fun `StdoutSink and StderrSink are classes whose settings can be read back`() {
        val sinks: List<LogSink> = listOf(
            StdoutSink(format = LogFormat.JSON, color = ColorMode.NEVER),
            StderrSink(name = "errors"),
        )

        val stdout = assertIs<StdoutSink>(sinks[0])
        val stderr = assertIs<StderrSink>(sinks[1])
        assertEquals(LogFormat.JSON, stdout.format)
        assertEquals(ColorMode.NEVER, stdout.color)
        assertEquals("stdout", stdout.name)
        assertEquals(LogFormat.TEXT, stderr.format)
        assertEquals("errors", stderr.name)
    }

    @OptIn(InternalKernApi::class)
    @Test
    fun `console sinks take one entry per write and never a batch`() {
        listOf(StdoutSink(), StderrSink(), PlatformSink()).forEach { sink ->
            assertFalse(sink is BatchSink, "${sink.name} batches")
        }
    }
}
