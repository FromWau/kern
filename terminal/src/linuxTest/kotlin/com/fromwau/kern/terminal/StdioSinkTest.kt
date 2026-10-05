package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.tmpfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class StdioSinkTest {

    @Test
    fun `a write the device refuses is reported`() {
        val full = assertNotNull(fopen("/dev/full", "w"))
        try {
            val sink = StdioSink(full)
            sink.write("text")
            assertTrue(sink.failed)
        } finally {
            fclose(full)
        }
    }

    @Test
    fun `a write that lands is not reported`() {
        val file = assertNotNull(tmpfile())
        try {
            val sink = StdioSink(file)
            sink.write("text")
            assertFalse(sink.failed)
        } finally {
            fclose(file)
        }
    }
}
