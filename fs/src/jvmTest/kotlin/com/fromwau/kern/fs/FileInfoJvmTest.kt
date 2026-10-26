package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

class FileInfoJvmTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a chmod moves changedAt and leaves modifiedAt`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val file = (dir / "a.txt").writeRaw("a")
        val nio = Paths.get(file.toString())
        Files.setLastModifiedTime(nio, FileTime.from(java.time.Instant.parse("2001-02-03T04:05:06Z")))
        val before = file.info().assertSuccess()
        // The kernel stamps a change from a clock that can trail this one by a tick.
        val chmodAt = Clock.System.now() - 1.seconds

        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-------"))
        val after = file.info().assertSuccess()

        val old = Instant.parse("2001-02-03T04:05:06Z")
        assertEquals(old, before.modifiedAt)
        assertEquals(old, after.modifiedAt)
        val changed = assertNotNull(after.changedAt)
        assertTrue(changed >= assertNotNull(before.changedAt), "changedAt went back: $before, then $after")
        assertTrue(changed >= chmodAt, "changedAt $changed is from before the chmod at $chmodAt")
        assertEquals(Permissions(0b110_000_000), after.permissions)
    }

    @Test
    fun `a file this process made is owned by it`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(true, file.info().assertSuccess().ownedByMe)
    }

    @Test
    fun `a file root owns is not owned by another user`() {
        val passwd = Path("/etc/passwd")
        assumeTrue(hasUnixView && passwd.exists(), "no /etc/passwd with unix attributes here")
        val ownUid = Files.getAttribute(Paths.get((dir / "a.txt").writeRaw("a").toString()), "unix:uid")
        val rootOwned = Files.getAttribute(Paths.get(passwd.toString()), "unix:uid") == 0
        assumeTrue(rootOwned && ownUid != 0, "this test runs as root, or /etc/passwd is not root's")

        assertEquals(false, passwd.info().assertSuccess().ownedByMe)
    }

    @Test
    fun `without the unix view the posix view gives the permission bits and the owner`() {
        assumeTrue(hasPosixView, "this file system has no posix permissions")
        val file = (dir / "a.txt").writeRaw("abc")
        val nio = Paths.get(file.toString())
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-r-----"))

        val info = fileInfoWith(file, views = AttributeViews.Posix)

        assertEquals(Permissions(0b110_100_000), info.permissions)
        assertEquals(true, info.ownedByMe)
        assertEquals(FileType.Regular, info.type)
        assertEquals(3L, info.size)
        assertEquals(file.modifiedAt().assertSuccess(), info.modifiedAt)
        assertEquals(null, info.changedAt)
    }

    @Test
    fun `info reads the richest view a file system offers`() {
        assertEquals(AttributeViews.Unix, AttributeViews.richest(unix = true, posix = true))
        assertEquals(AttributeViews.Posix, AttributeViews.richest(unix = false, posix = true))
        assertEquals(AttributeViews.Basic, AttributeViews.richest(unix = false, posix = false))
    }

    @Test
    fun `with only the basic view permissions changedAt and the owner are unknown`() {
        val file = (dir / "a.txt").writeRaw("abc")

        val info = fileInfoWith(file, views = AttributeViews.Basic)

        assertEquals(FileInfo(FileType.Regular, 3L, file.modifiedAt().assertSuccess(), null, null, null, null), info)
    }

    @Test
    fun `info of a FIFO names it as fileType does`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val fifo = dir / "fifo"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")

        assertEquals(FileType.Fifo, fifo.info().assertSuccess().type)
        assertEquals(fifo.fileType().assertSuccess(), fifo.info().assertSuccess().type)
    }
}
