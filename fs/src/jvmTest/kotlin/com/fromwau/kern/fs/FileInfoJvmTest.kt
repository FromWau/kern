package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.io.files.Path
import java.nio.file.Path as NioPath
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
    fun `a file root owns gives every field, not owned by me`() {
        val passwd = Path("/etc/passwd")
        assumeTrue(hasUnixView && passwd.exists(), "no /etc/passwd with unix attributes here")
        assumeTrue(ownedByRootNotMe(passwd), "this test runs as root, or /etc/passwd is not root's")

        val info = passwd.info().assertSuccess()

        assertEquals(false, info.ownedByMe)
        assertEquals(unixView(passwd), info)
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
        assertEquals(null, info.links)
    }

    @Test
    fun `the posix view names a folder, a link's own type and another user's file`() {
        assumeTrue(hasPosixView, "this file system has no posix permissions")
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        val link = dir / "link"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(folder.toString()))
        val passwd = Path("/etc/passwd")

        assertEquals(FileType.Directory, fileInfoWith(folder, views = AttributeViews.Posix).type)
        assertEquals(FileType.Directory, fileInfoWith(link, views = AttributeViews.Posix).type)
        assertEquals(FileType.Symlink, fileInfoWith(link, AttributeViews.Posix, LinkOption.NOFOLLOW_LINKS).type)
        val rootsFile = hasUnixView && passwd.exists() && ownedByRootNotMe(passwd)
        assumeTrue(rootsFile, "this test runs as root, or /etc/passwd is not root's")
        assertEquals(false, fileInfoWith(passwd, views = AttributeViews.Posix).ownedByMe)
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

    @Test
    fun `info of a file gives every field as the unix view holds it`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val file = (dir / "a.txt").writeRaw("abc")
        val nio = Paths.get(file.toString())
        Files.setAttribute(nio, "unix:mode", 0b110_100_000)
        Files.setLastModifiedTime(nio, FileTime.from(java.time.Instant.parse("2001-02-03T04:05:06Z")))
        Files.createLink(Paths.get((dir / "b.txt").toString()), nio)

        val info = file.info().assertSuccess()

        assertEquals(FileType.Regular, info.type)
        assertEquals(3L, info.size)
        assertEquals(Instant.parse("2001-02-03T04:05:06Z"), info.modifiedAt)
        assertRecent(info.changedAt)
        assertEquals(Permissions(0b110_100_000), info.permissions)
        assertEquals(true, info.ownedByMe)
        assertEquals(2, info.links)
        assertEquals(unixView(file), info)
    }

    @Test
    fun `info of a folder gives every field as the unix view holds it`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        val nio = Paths.get(folder.toString())
        Files.setAttribute(nio, "unix:mode", 0b001_111_101_000)
        Files.setLastModifiedTime(nio, FileTime.from(java.time.Instant.parse("2001-02-03T04:05:06Z")))

        val info = folder.info().assertSuccess()

        assertEquals(FileType.Directory, info.type)
        assertEquals(Instant.parse("2001-02-03T04:05:06Z"), info.modifiedAt)
        assertRecent(info.changedAt)
        assertEquals(Permissions(0b001_111_101_000), info.permissions)
        assertEquals(true, info.ownedByMe)
        assertEquals(unixView(folder), info)
    }

    @Test
    fun `info through a symlink gives the target's fields, and the link's own lookup the link's`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val file = (dir / "a.txt").writeRaw("abc")
        val link = dir / "link"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(file.toString()))

        assertEquals(unixView(file), link.info().assertSuccess())
        assertEquals(unixView(link, LinkOption.NOFOLLOW_LINKS), ownFileInfoAt(link))
        assertEquals(FileType.Symlink, ownFileInfoAt(link).type)
    }

    @Test
    fun `info of a FIFO gives every field as the unix view holds it`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val fifo = dir / "fifo"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")
        Files.setAttribute(Paths.get(fifo.toString()), "unix:mode", 0b110_000_000)

        val info = fifo.info().assertSuccess()

        assertEquals(FileType.Fifo, info.type)
        assertEquals(0L, info.size)
        assertRecent(info.changedAt)
        assertEquals(Permissions(0b110_000_000), info.permissions)
        assertEquals(true, info.ownedByMe)
        assertEquals(1, info.links)
        assertEquals(unixView(fifo), info)
    }

    @Test
    fun `info reads no owner or group name, which costs a user database lookup per file`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val file = Paths.get((dir / "a.txt").writeRaw("abc").toString())

        val read = unixAttributes(file, emptyArray()).keys

        assertEquals(setOf("mode", "size", "lastModifiedTime", "ctime", "uid", "nlink"), read)
    }

    @Test
    fun `the posix view reads no group name, which costs a user database lookup per file`() {
        assumeTrue(hasPosixView, "this file system has no posix permissions")
        val file = Paths.get((dir / "a.txt").writeRaw("abc").toString())

        val read = posixAttributes(file, emptyArray()).keys

        assertEquals(
            setOf("permissions", "owner", "size", "lastModifiedTime", "isRegularFile", "isDirectory", "isSymbolicLink"),
            read,
        )
    }

    @Test
    fun `the user this process runs as is looked up once, not per file`() {
        assumeTrue(hasUnixView, "this file system has no unix attributes")
        val files = listOf("a.txt", "b.txt", "c.txt").map { (dir / it).writeRaw("abc") }
        var lookUps = 0
        val me = CurrentUser(
            uid = null,
            lookUp = {
                lookUps++
                ownUser()
            },
        )

        val unix = files.map { fileInfoWith(it, AttributeViews.Unix, me = me).ownedByMe }
        val posix = files.map { fileInfoWith(it, AttributeViews.Posix, me = me).ownedByMe }

        assertEquals(listOf(true, true, true), unix)
        assertEquals(listOf(true, true, true), posix)
        assertEquals(1, lookUps)
    }

    @Test
    fun `without the uid a file's owner is looked up once per uid`() {
        val passwd = Path("/etc/passwd")
        assumeTrue(hasUnixView && passwd.exists(), "no /etc/passwd with unix attributes here")
        assumeTrue(ownedByRootNotMe(passwd), "this test runs as root, or /etc/passwd is not root's")
        val files = listOf("a.txt", "b.txt", "c.txt").map { (dir / it).writeRaw("abc") } + passwd
        val ownerReads = mutableListOf<NioPath>()
        val me = CurrentUser(
            uid = null,
            lookUp = ::ownUser,
            ownerOf = { file, options ->
                ownerReads.add(file)
                Files.readAttributes(file, "unix:uid,owner", *options)
            },
        )

        val owned = (files + files).map { fileInfoWith(it, AttributeViews.Unix, me = me).ownedByMe }

        assertEquals(listOf(true, true, true, false, true, true, true, false), owned)
        assertEquals(2, ownerReads.size, "owners read for $ownerReads")
    }

    private fun ownUser(): UserPrincipal =
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))

    /** Every field of [FileInfo] for [path], read straight from java.nio's whole unix view. */
    private fun unixView(
        path: Path,
        vararg options: LinkOption,
    ): FileInfo {
        assumeTrue(currentUid != null, "this runtime cannot name its uid")
        val unix = Files.readAttributes(Paths.get(path.toString()), "unix:*", *options)
        val mode = unix.getValue("mode") as Int
        return FileInfo(
            type = fileTypeOfMode(mode),
            size = unix.getValue("size") as Long,
            modifiedAt = (unix.getValue("lastModifiedTime") as FileTime).toKotlinInstant(),
            changedAt = (unix.getValue("ctime") as FileTime).toKotlinInstant(),
            permissions = Permissions(mode and 0b111_111_111_111),
            ownedByMe = unix.getValue("uid") == currentUid,
            links = unix.getValue("nlink") as Int,
        )
    }

    private fun ownedByRootNotMe(path: Path): Boolean =
        Files.getAttribute(Paths.get(path.toString()), "unix:uid") == 0 && ownUid != 0

    private val ownUid by lazy { Files.getAttribute(Paths.get((dir / "own").writeRaw("").toString()), "unix:uid") }

    // The kernel stamps a change from a clock that can trail this one by a tick.
    private val startedAt = Clock.System.now() - 1.seconds

    private fun assertRecent(changedAt: Instant?) {
        val at = assertNotNull(changedAt)
        assertTrue(at >= startedAt && at <= Clock.System.now(), "changedAt $at is not from this test")
    }

    private fun FileTime.toKotlinInstant(): Instant =
        toInstant().let { Instant.fromEpochSeconds(it.epochSecond, it.nano) }
}
