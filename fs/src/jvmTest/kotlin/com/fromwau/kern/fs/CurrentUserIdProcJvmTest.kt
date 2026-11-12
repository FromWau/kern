package com.fromwau.kern.fs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

class CurrentUserIdProcJvmTest {
    @Test
    fun `the uid is the effective uid Linux reports for this process`() {
        val status = File("/proc/self/status")
        assumeTrue(status.exists(), "no /proc/self/status outside Linux")
        val effective = status.readLines()
            .first { it.startsWith("Uid:") }
            .split(Regex("\\s+"))[2]
            .toLong()

        assertEquals(effective, currentUserId())
    }
}
