package com.fromwau.kern.fs

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.IOException
import kotlinx.io.files.Path

class ReleaseFailedDefaultJvmTest {
    @Test
    fun `by default a failed release prints a warning naming the lock file then the stack trace to stderr`() {
        val printed = ByteArrayOutputStream()
        val before = System.err
        System.setErr(PrintStream(printed, true))
        try {
            FileLocks.onReleaseFailed(Path("/run/app/.app.lock"), IOException("the OS kept the lock"))
        } finally {
            System.setErr(before)
        }

        val lines = printed.toString().lines()
        assertEquals("kern.fs: warning: the lock on /run/app/.app.lock was not released", lines[0])
        assertEquals("java.io.IOException: the OS kept the lock", lines[1])
        assertTrue(lines[2].trimStart().startsWith("at "), lines[2])
    }
}
