package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.io.files.Path
import platform.posix.geteuid

class FileInfoLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a chmod moves changedAt and leaves modifiedAt`() {
        val file = (dir / "a.txt").writeRaw("a")
        file.setModifiedTime(epochSeconds = 981_173_106, microseconds = 0)
        val before = file.info().assertSuccess()
        // The kernel stamps a change from a clock that can trail this one by a tick.
        val chmodAt = Clock.System.now() - 1.seconds

        file.setMode(0b110_000_000)
        val after = file.info().assertSuccess()

        val old = Instant.fromEpochSeconds(981_173_106)
        assertEquals(old, before.modifiedAt)
        assertEquals(old, after.modifiedAt)
        val changed = assertNotNull(after.changedAt)
        assertTrue(changed >= assertNotNull(before.changedAt), "changedAt went back: $before, then $after")
        assertTrue(changed >= chmodAt, "changedAt $changed is from before the chmod at $chmodAt")
        assertEquals(Permissions(0b110_000_000), after.permissions)
    }

    @Test
    fun `a file this process made is owned by it`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(true, file.info().assertSuccess().ownedByMe)
    }

    @Test
    fun `a file root owns is not owned by another user`() {
        // Root owns /etc/passwd, so only a run as root owns it too, and that run has no other user's file to ask about.
        if (geteuid() == 0u || !Path("/etc/passwd").exists()) return

        assertEquals(false, Path("/etc/passwd").info().assertSuccess().ownedByMe)
    }

    @Test
    fun `info of a FIFO and a character device names them as fileType does`() {
        val fifo = (dir / "fifo").makeFifo()
        val device = Path("/dev/null")

        assertEquals(FileType.Fifo, fifo.info().assertSuccess().type)
        assertEquals(FileType.CharacterDevice, device.info().assertSuccess().type)
        assertEquals(Ok(FileType.CharacterDevice), device.fileType())
    }

    @Test
    fun `info and fileType name a block device`() {
        val block = firstBlockDevice() ?: return println("skipped: no block device in /dev")

        assertEquals(FileType.BlockDevice, block.info().assertSuccess().type)
        assertEquals(Ok(FileType.BlockDevice), block.fileType())
    }

    @Test
    fun `info keeps the setgid and sticky bits a folder carries`() {
        val folder = dir / "shared"
        folder.createDirectories().assertSuccess()
        folder.setMode(0b011_111_111_101)

        assertEquals(Permissions(0b011_111_111_101), folder.info().assertSuccess().permissions)
        assertEquals(Ok(Permissions(0b011_111_111_101)), folder.permissions())
    }
}
