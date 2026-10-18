package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGPIPE
import platform.posix.SIG_DFL
import platform.posix.SIG_IGN
import platform.posix.signal
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
private fun ignoreBrokenPipes() {
    signal(SIGPIPE, SIG_IGN)
}

// The README's snippet for a POSIX source set, kept here so it compiles against the real platform.posix.
@OptIn(ExperimentalForeignApi::class)
class ReadmeSnippetsLinuxTest {

    @Test
    fun `the SIGPIPE opt-out ignores the signal`() {
        val previous = signal(SIGPIPE, SIG_DFL)
        ignoreBrokenPipes()
        assertEquals(SIG_IGN, signal(SIGPIPE, previous))
    }
}
