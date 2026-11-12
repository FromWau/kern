package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.geteuid

class CurrentUserIdLinuxTest {
    @Test
    fun `the uid is the effective one the system reports`() {
        assertEquals(geteuid().toLong(), currentUserId())
    }
}
