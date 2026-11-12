package com.fromwau.kern.fs

import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentUserIdTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `there is a uid wherever files have permissions and none on Windows`() {
        val hasPermissions = dir.permissions().assertSuccess() != null

        assertEquals(hasPermissions, currentUserId() != null)
    }
}
