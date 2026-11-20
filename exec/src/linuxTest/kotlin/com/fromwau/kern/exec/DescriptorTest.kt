@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.exec

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.F_DUPFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.open
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptorTest {
    @Test
    fun `a descriptor opened without close-on-exec does not reach the child`() {
        val opened = open("/dev/null", O_RDONLY)
        // Far above what the shell opens for itself, so finding it open can only mean it was inherited.
        val fd = fcntl(opened, F_DUPFD, 200)
        close(opened)
        assertTrue(fd >= 200, "fcntl gave $fd")
        try {
            val script = "if [ -e /proc/self/fd/$1 ]; then echo open; else echo closed; fi"
            assertEquals("closed", sh(script, fd.toString()).events().text(ChildStream.Stdout).trim())
        } finally {
            close(fd)
        }
    }
}
