package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RenameProbesTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = dir.deleteTree()

    @Test
    fun `a fresh file has one link and is owned by this user`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(FileIdentity(linkCount = 1, ownedByCurrentUser = true), fileIdentity(file))
    }

    @Test
    fun `both names of a hard linked file report two links`() {
        val first = (dir / "first.txt").writeRaw("a")
        val second = dir / "second.txt"
        second.hardLinkTo(first).assertSuccess()

        assertEquals(2L, fileIdentity(first)?.linkCount)
        assertEquals(2L, fileIdentity(second)?.linkCount)
    }

    @Test
    fun `a missing path has no identity`() {
        assertNull(fileIdentity(dir / "missing.txt"))
    }

    @Test
    fun `syncing a directory succeeds`() {
        assertNull(syncDirectory(dir))
    }
}
