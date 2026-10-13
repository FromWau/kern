package com.fromwau.kern.fs

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RenameProbesLinuxTest {
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
        val second = (dir / "second.txt").hardLinkTo(first)

        assertEquals(2L, fileIdentity(first)?.linkCount)
        assertEquals(2L, fileIdentity(second)?.linkCount)
    }

    @Test
    fun `a missing path has no identity`() {
        assertNull(fileIdentity(dir / "missing.txt"))
    }

    @Test
    fun `syncing a written file succeeds`() {
        assertNull(syncFile((dir / "a.txt").writeRaw("a")))
    }

    @Test
    fun `syncing a missing file gives a reason`() {
        assertNotNull(syncFile(dir / "missing.txt"))
    }

    @Test
    fun `syncing a directory succeeds`() {
        assertNull(syncDirectory(dir))
    }
}
