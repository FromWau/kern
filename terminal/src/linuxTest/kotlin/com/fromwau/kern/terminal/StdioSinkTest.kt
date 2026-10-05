package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.fclose
import platform.posix.fdopen
import platform.posix.fopen
import platform.posix.pipe
import platform.posix.signal
import platform.posix.tmpfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
class StdioSinkTest {

    @Test
    fun `a write the device refuses is Refused`() {
        val full = assertNotNull(fopen("/dev/full", "w"))
        try {
            val sink = StdioSink(full, Stream.Out)
            sink.write("text")
            assertEquals(Stream.Out, assertIs<WriteError.Refused>(sink.failure).stream)
        } finally {
            fclose(full)
        }
    }

    @Test
    fun `a write to a pipe whose reader is gone is BrokenPipe`() {
        // Ignored for the test: by default the signal ends the process before the write can fail.
        val previous = signal(SIGPIPE, SIG_IGN)
        try {
            val writer = memScoped {
                val ends = allocArray<IntVar>(2)
                check(pipe(ends) == 0)
                close(ends[0])
                assertNotNull(fdopen(ends[1], "w"))
            }
            try {
                val sink = StdioSink(writer, Stream.Out)
                sink.write("text")
                assertEquals(WriteError.BrokenPipe(Stream.Out), sink.failure)
            } finally {
                fclose(writer)
            }
        } finally {
            signal(SIGPIPE, previous)
        }
    }

    @Test
    fun `a write that lands is no failure`() {
        val file = assertNotNull(tmpfile())
        try {
            val sink = StdioSink(file, Stream.Out)
            sink.write("text")
            assertNull(sink.failure)
        } finally {
            fclose(file)
        }
    }
}
