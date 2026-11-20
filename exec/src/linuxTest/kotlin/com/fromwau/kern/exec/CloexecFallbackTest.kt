@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.exec

import com.fromwau.kern.exec.spawn.kern_exec_cloexec_from
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fcntl
import platform.posix.open
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class CloexecFallbackTest {
    @Test
    fun `the fallback for kernels before 5_11 marks an open descriptor quickly under a container-sized limit`() {
        val fd = open("/dev/null", O_RDONLY)
        assertTrue(fd >= 3, "open gave $fd")
        try {
            // Container runtimes can set the open-file limit near 2^30; a loop up to it takes minutes.
            val took = measureTime { kern_exec_cloexec_from(3, 1L shl 27) }
            assertEquals(FD_CLOEXEC, fcntl(fd, F_GETFD) and FD_CLOEXEC, "the descriptor was not marked")
            assertTrue(took < 1.seconds, "took $took")
        } finally {
            close(fd)
        }
    }
}
