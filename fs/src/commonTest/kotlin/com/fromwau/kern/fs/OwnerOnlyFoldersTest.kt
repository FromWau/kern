package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.SystemFileSystem

class OwnerOnlyFoldersTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `createDirectories with ownerOnly makes each new level for its owner alone`() {
        assertEquals(Ok(Unit), (dir / "a" / "b" / "c").createDirectories(ownerOnly = true))

        (dir / "a").assertPermissions(OWNER_ONLY)
        (dir / "a" / "b").assertPermissions(OWNER_ONLY)
        (dir / "a" / "b" / "c").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `createDirectories with ownerOnly leaves a level already there as it is`() {
        createDirectoriesRaw(dir / "a")
        (dir / "a").chmod(OPEN_FOLDER).assertSuccess()

        assertEquals(Ok(Unit), (dir / "a" / "b").createDirectories(ownerOnly = true))

        (dir / "a").assertPermissions(OPEN_FOLDER)
        (dir / "a" / "b").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `createDirectories with ownerOnly of a folder already there leaves it as it is`() {
        createDirectoriesRaw(dir / "a")
        (dir / "a").chmod(OPEN_FOLDER).assertSuccess()

        assertEquals(Ok(Unit), (dir / "a").createDirectories(ownerOnly = true))

        (dir / "a").assertPermissions(OPEN_FOLDER)
    }

    @Test
    fun `a write with ownerOnly makes its missing folders for their owner alone`() {
        (dir / "x" / "y" / "f").writeText("a", ownerOnly = true).assertSuccess()

        (dir / "x").assertPermissions(OWNER_ONLY)
        (dir / "x" / "y").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `writeBytes with ownerOnly makes its missing folders for their owner alone`() {
        (dir / "x" / "f").writeBytes(byteArrayOf(1), ownerOnly = true).assertSuccess()

        (dir / "x").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `copyTo with ownerOnly makes its missing folders for their owner alone`() {
        val source = (dir / "source").writeRaw("a")

        source.copyTo(dir / "x" / "f", ownerOnly = true).assertSuccess()

        (dir / "x").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `openAppend with ownerOnly makes its missing folders for their owner alone`() {
        (dir / "x" / "app.log").openAppend(ownerOnly = true).assertSuccess().close()

        (dir / "x").assertPermissions(OWNER_ONLY)
    }

    @Test
    fun `a write without ownerOnly makes its missing folders as the umask leaves them`() {
        SystemFileSystem.createDirectories(dir / "probe")
        val umaskFolder = (dir / "probe").permissions().assertSuccess()

        (dir / "x" / "f").writeText("a").assertSuccess()

        assertEquals(umaskFolder, (dir / "x").permissions().assertSuccess())
    }

    private companion object {
        const val OWNER_ONLY = 0b111_000_000
        const val OPEN_FOLDER = 0b111_101_101
    }
}
