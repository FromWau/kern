package com.fromwau.kern.fs

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

class CurrentUserIdJvmTest {
    @Test
    fun `the uid is the owner of a file this process makes`() {
        assumeTrue(hasUnixView, "no unix:uid on this file system")
        val fresh = Files.createTempFile("kern-uid-test", null)
        val owner = try {
            Files.getAttribute(fresh, "unix:uid") as Int
        } finally {
            Files.delete(fresh)
        }

        assertEquals(owner.toUInt().toLong(), currentUserId())
    }
}
