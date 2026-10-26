package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWGRP
import platform.posix.S_IWUSR
import platform.posix.S_IXGRP
import platform.posix.S_IXOTH
import platform.posix.S_IXUSR

class FilesLinuxTest {
    private val dir = newTempDir()
    private val elsewhere = newTempDir()
    private val links = mutableListOf<Path>()
    private val tooLarge = ByteArray(64 * 1024) { 'x'.code.toByte() }
    private val device = Path("/dev/null")

    @AfterTest
    fun cleanUp() {
        links.filter { it.isLink() }.forEach { it.removeLink() }
        dir.deleteTree()
        elsewhere.deleteTree()
    }

    @Test
    fun `an Auto write through a symlink replaces the target by rename and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("old")
        val link = (dir / "link.toml")
            .rawSymlinkTo(real)
            .also { links += it }
        val before = real.inode()

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Auto).way())

        assertTrue(link.isLink())
        assertEquals("new", link.readRaw())
        assertNotEquals(before, real.inode())
    }

    @Test
    fun `a first Auto write through a dangling symlink creates its target and keeps the link`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .rawSymlinkTo(destination)
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Auto).way())

        assertTrue(link.isLink())
        assertEquals("new", destination.readRaw())
    }

    @Test
    fun `a first Auto write through a dangling symlink that fails leaves a temp file and no target`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .rawSymlinkTo(destination)
            .also { links += it }

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { link.writeBytes(tooLarge, WriteMode.Auto) }

        dir.withModeRestored {
            result.assertError<FileError.WriteFailed>()
            assertFalse(SystemFileSystem.exists(destination))
            assertEquals(1, link.leftoverCopies<Leftover.Temp>().assertSuccess().size)
        }
    }

    @Test
    fun `an Atomic write through a dangling symlink creates its target and keeps the link`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .rawSymlinkTo(destination)
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Atomic).way())

        assertTrue(link.isLink())
        assertEquals("new", destination.readRaw())
    }

    @Test
    fun `a Replace write through a dangling symlink creates its target and keeps the link`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .rawSymlinkTo(destination)
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Replace).way())

        assertTrue(link.isLink())
        assertEquals("new", destination.readRaw())
    }

    @Test
    fun `a write through a dangling relative symlink lands beside the link with the folders it needs`() {
        val link = (dir / "link.toml")
            .rawSymlinkTo(Path("sub", "dest.toml"))
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Atomic).way())

        assertTrue(link.isLink())
        assertEquals("new", (dir / "sub" / "dest.toml").readRaw())
    }

    @Test
    fun `a write through a chain of links ending in a missing file creates that file and keeps every link`() {
        val destination = dir / "deep" / "dest.toml"
        val second = (dir / "second.toml")
            .rawSymlinkTo(destination)
            .also { links += it }
        val first = (dir / "first.toml")
            .rawSymlinkTo(Path("second.toml"))
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), first.writeText("new", WriteMode.Auto).way())

        assertTrue(first.isLink())
        assertTrue(second.isLink())
        assertEquals("new", destination.readRaw())
    }

    @Test
    fun `leftovers finds the temp file beside the missing target of a dangling symlink`() {
        val link = (dir / "link.toml")
            .rawSymlinkTo(dir / "dest.toml")
            .also { links += it }
        val temp = (dir / ".dest.toml.$A_WRITE_ID.tmp").writeRaw("half")

        assertEquals(Ok(listOf(temp)), link.leftoverCopies<Leftover.Temp>())
    }

    @Test
    fun `an Auto write replaces a lone file by rename and says Renamed keeping its permissions`() {
        val file = (dir / "secret.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)
        val before = file.inode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Auto).way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.inode())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an InPlace copyTo onto a symlink to itself keeps the content`() {
        val file = (dir / "a.toml").writeRaw("content")
        val link = (dir / "link.toml")
            .rawSymlinkTo(file)
            .also { links += it }

        assertIs<Copied.Unchanged>(file.copyTo(link, WriteMode.InPlace).assertSuccess())

        assertEquals("content", file.readRaw())
    }

    @Test
    fun `a failed InPlace write puts the old content back into the file so its other hard links see it too`() {
        val file = (dir / "a.toml").writeRaw("old")
        val other = (dir / "b.toml").rawHardLinkTo(file)

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge, WriteMode.InPlace) }

        result.assertError<FileError.WriteFailed>()
        assertEquals(file.inode(), other.inode())
        assertEquals("old", other.readRaw())
        assertEquals(setOf(file, other), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `a Replace write that cannot keep the group takes the group's permissions away`() {
        val file = (dir / "shared.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP)

        val written = file.write(WriteMode.Replace, keepGroup = { _, _ -> false }) {
            it.write("new".encodeToByteArray())
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals("new", file.readRaw())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `copyTo refuses a FIFO source without opening it`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.copyTo(dir / "b.txt"))
    }

    @Test
    fun `copyTo of a source that may not be read is Inaccessible and leaves the target alone`() {
        val source = (dir / "secret.txt").writeRaw("secret")
        val target = (dir / "b.txt").writeRaw("kept")
        source.setMode(0)
        try {
            // A runner that may read it anyway, such as root, leaves nothing to observe.
            if (source.canOpenForReading()) return

            assertEquals(source, source.copyTo(target).assertError<FileError.Inaccessible>().path)
            assertEquals("kept", target.readRaw())
        } finally {
            source.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `copyTo leaves the target its own permissions`() {
        val source = (dir / "a.txt").writeRaw("new")
        source.setMode(S_IRWXU)
        val target = (dir / "b.txt").writeRaw("old")
        target.setMode(S_IRUSR or S_IWUSR)

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())

        assertEquals("new", target.readRaw())
        assertEquals(S_IRUSR or S_IWUSR, target.mode())
    }

    @Test
    fun `copyTo gives a new target the permissions any new file gets`() {
        val source = (dir / "a.txt").writeRaw("new")
        source.setMode(S_IRWXU)
        val fresh = (dir / "fresh.txt").writeRaw("x")
        val target = dir / "b.txt"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())

        assertEquals(fresh.mode(), target.mode())
    }

    @Test
    fun `an Auto write that fails midway leaves the file itself untouched and no temp file`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)
        val before = file.inode()

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge, WriteMode.Auto) }

        result.assertError<FileError.WriteFailed>()
        assertEquals("old", file.readRaw())
        assertEquals(before, file.inode())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an Atomic write in a folder that may not be written is refused and changes nothing`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            file.writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()
            assertEquals("old", file.readRaw())
            assertEquals(listOf(file), SystemFileSystem.list(folder).toList())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `moveTo moves a symlink as itself and leaves its target where it is`() {
        val real = (dir / "real.toml").writeRaw("kept")
        val link = (dir / "link.toml").rawSymlinkTo(real)
        val moved = (dir / "moved.toml").also { links += it }

        assertEquals(Ok(Unit), link.moveTo(moved))

        assertTrue(moved.isLink())
        assertEquals("kept", real.readRaw())
    }

    @Test
    fun `moveTo of a symlink to a file onto a folder is NotRegularFile naming the folder and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("kept")
        val link = (dir / "link.toml").rawSymlinkTo(real).also { links += it }
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(link.isLink())
    }

    @Test
    fun `moveTo of a dangling symlink onto a folder with entries is NotRegularFile naming the folder`() {
        val link = (dir / "link.toml").rawSymlinkTo(dir / "missing.toml").also { links += it }
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(link.isLink())
    }

    @Test
    fun `moveTo of a symlink to a folder onto a folder with entries is NotRegularFile naming the folder`() {
        val real = (dir / "real").also { createDirectoriesRaw(it) }
        val link = (dir / "link").rawSymlinkTo(real).also { links += it }
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(link.isLink())
    }

    @Test
    fun `moveTo of a symlink to a folder onto an empty folder is NotRegularFile naming the folder`() {
        val real = (dir / "real").also { createDirectoriesRaw(it) }
        val link = (dir / "link").rawSymlinkTo(real).also { links += it }
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(link.isLink())
        assertTrue(SystemFileSystem.metadataOrNull(folder)?.isDirectory == true)
    }

    @Test
    fun `moveTo of a dangling symlink into a folder that may not be written is WriteFailed`() {
        val link = (dir / "link.toml").rawSymlinkTo(dir / "missing.toml").also { links += it }
        val readOnly = (dir / "readonly").also { createDirectoriesRaw(it) }
        readOnly.setMode(S_IRUSR or S_IXUSR)
        readOnly.withModeRestored {
            link.moveTo(readOnly / "b.toml").assertError<FileError.WriteFailed>()
        }
    }

    @Test
    fun `moveTo replaces an empty folder and refuses one with entries`() {
        val from = (dir / "from").also { createDirectoriesRaw(it) }
        val empty = (dir / "empty").also { createDirectoriesRaw(it) }
        val full = (dir / "full").also { createDirectoriesRaw(it) }
        (full / "entry").writeRaw("a")

        from.moveTo(full).assertError<FileError.WriteFailed>()
        assertEquals(Ok(Unit), from.moveTo(empty))
        assertFalse(from.exists())
    }

    @Test
    fun `an InPlace write keeps the file itself with its permissions`() {
        val file = (dir / "secret.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)
        val inode = file.inode()

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", WriteMode.InPlace).way())
        assertEquals("new", file.readRaw())
        assertEquals(inode, file.inode())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `an Auto write onto a read-only file is WriteFailed for Denied and leaves it intact`() =
        assertReadOnlyRefused { it.writeText("new", WriteMode.Auto) }

    @Test
    fun `an Atomic write onto a read-only file is WriteFailed for Denied and leaves it intact`() =
        assertReadOnlyRefused { it.writeText("new", WriteMode.Atomic) }

    @Test
    fun `an InPlace write onto a read-only file is WriteFailed for Denied and leaves it intact`() =
        assertReadOnlyRefused { it.writeText("new", WriteMode.InPlace) }

    @Test
    fun `a Replace write onto a read-only file is WriteFailed for Denied and leaves it intact`() =
        assertReadOnlyRefused { it.writeText("new", WriteMode.Replace) }

    @Test
    fun `copyTo onto a read-only file is WriteFailed for Denied and leaves it intact`() =
        assertReadOnlyRefused { target -> (dir / "source.toml").writeRaw("new").copyTo(target) }

    @Test
    fun `a write through a link onto a read-only file names the file the link leads to as where it was refused`() {
        val file = (dir / "theirs.toml").writeRaw("old")
        val link = (dir / "a.toml")
            .rawSymlinkTo(file)
            .also { links += it }
        file.setMode(S_IRUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForWriting()) return

            val failure = link.writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()

            assertEquals(link, failure.path)
            assertEquals(FailedAt.LinkTarget(file), failure.at)
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `a write of a relative path onto a read-only file names no other path as where it was refused`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR)
        val previous = currentDirectory()
        changeDirectory(dir)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForWriting()) return

            val failure = Path("a.toml").writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()

            assertEquals(Path("a.toml"), failure.path)
            assertNull(failure.at)
        } finally {
            changeDirectory(previous)
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    /** Runs [write] on a file of mode 444 and checks it is refused for Denied, leaving the file as it was. */
    private fun assertReadOnlyRefused(write: (Path) -> Result<Copied, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        val readOnly = S_IRUSR or S_IRGRP or S_IROTH
        file.setMode(readOnly)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForWriting()) return

            val failure = write(file).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertNull(failure.at)
            assertEquals("$file: Permission denied", failure.exception?.message)
            assertEquals("old", file.readRaw())
            assertEquals(readOnly, file.mode())
            assertEquals(emptyList(), file.leftoverCopies<Leftover.Temp>().assertSuccess())
            assertEquals(emptyList(), file.leftoverCopies<Leftover.Backup>().assertSuccess())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `writeText refuses a file that may not be read for Denied and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IWUSR)
        try {
            // A runner that may read it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForReading()) return

            assertEquals(IoCause.Denied, file.writeText("new").assertError<FileError.WriteFailed>().cause)
            assertEquals(S_IWUSR, file.mode())
            assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
        assertEquals("old", file.readRaw())
    }

    @Test
    fun `a write through a dangling link whose target's folder may not be made names the file and the level refused`() {
        if (Path("/").canCreateFileIn()) return
        val missing = Path("/nonexistent-kern-${Uuid.random()}")
        val out = dir / "out"
        createDirectoriesRaw(out)
        val file = (out / "summary.txt").rawSymlinkTo(missing / "zzz")
        val source = (dir / "source.txt").writeRaw("x")
        try {
            val refused = Err(FileError.WriteFailed(file, IoCause.Denied, null, at = FailedAt.Folder(missing)))

            assertEquals(refused, file.writeText("x"))
            assertEquals(refused, file.writeBytes(byteArrayOf(1), WriteMode.InPlace))
            assertEquals(refused, source.copyTo(file))
        } finally {
            file.removeLink()
        }
    }

    @Test
    fun `a write whose folders cannot be made in a read-only folder names the file and the level refused`() {
        val out = dir / "out"
        createDirectoriesRaw(out)
        val file = out / "a" / "b.txt"
        val source = (dir / "source.txt").writeRaw("x")
        out.setMode(S_IRUSR or S_IXUSR)
        try {
            if (out.canCreateFileIn()) return
            val refused = Err(FileError.WriteFailed(file, IoCause.Denied, null, at = FailedAt.Folder(out / "a")))

            assertEquals(refused, file.writeText("x"))
            assertEquals(refused, source.copyTo(file, WriteMode.InPlace))
            assertEquals(Err(FileError.WriteFailed(out / "a", IoCause.Denied, null)), (out / "a").createDirectories())
        } finally {
            out.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a folder made in a read-only folder is Denied for nested levels and names the claim of a staged folder`() {
        val out = dir / "out"
        createDirectoriesRaw(out)
        out.setMode(S_IRUSR or S_IXUSR)
        try {
            if (out.canCreateFileIn()) return

            val nested = Err(FileError.WriteFailed(out / "a", IoCause.Denied, null))
            assertEquals(nested, (out / "a" / "b").createDirectories())
            val staged = (out / "c").createDirectoryAtomically { }.assertError<FileError.WriteFailed>()
            assertEquals(out / "c", staged.path)
            assertEquals(IoCause.Denied, staged.cause)
            val claim = assertIs<FailedAt.Claim>(staged.at).path
            assertEquals(out, claim.parent)
            assertTrue(claim.name.startsWith(".c.") && claim.name.endsWith(".lock"))
        } finally {
            out.setMode(S_IRWXU)
        }
        assertEquals(emptyList(), SystemFileSystem.list(out).toList())
    }

    @Test
    fun `a write into a folder that may not be written is WriteFailed for Denied and changes nothing`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            assertEquals(IoCause.Denied, file.writeText("new").assertError<FileError.WriteFailed>().cause)
            assertEquals("old", file.readRaw())
            assertEquals(listOf(file), SystemFileSystem.list(folder).toList())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a new file written into a folder that may not be written is WriteFailed for Denied`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            if (folder.canCreateFileIn()) return

            val failure = (folder / "a.toml").writeText("new").assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            val temp = assertIs<FailedAt.Temp>(failure.at).path
            assertEquals(folder, temp.parent)
            assertTrue(temp.name.startsWith(".a.toml.") && temp.name.endsWith(".tmp"))
            assertEquals("$temp: Permission denied", failure.exception?.message)
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a write through a link into a folder that may not be written names the link and the temp it was refused`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val target = (folder / "theirs.toml").writeRaw("old")
        val link = (dir / "a.toml")
            .rawSymlinkTo(target)
            .also { links += it }
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            if (folder.canCreateFileIn()) return

            val failure = link.writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()

            assertEquals(link, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            val temp = assertIs<FailedAt.Temp>(failure.at).path
            assertEquals(folder, temp.parent)
            assertTrue(temp.name.startsWith(".theirs.toml.") && temp.name.endsWith(".tmp"))
            assertEquals("$temp: Permission denied", failure.exception?.message)
            assertEquals("old", target.readRaw())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a new file written InPlace into a folder that may not be written is WriteFailed for Denied`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            if (folder.canCreateFileIn()) return

            val failure = (folder / "a.toml").writeText("new", WriteMode.InPlace).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertNull(failure.at)
            assertEquals("${folder / "a.toml"}: Permission denied", failure.exception?.message)
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `an InPlace write in a folder that may not be written is WriteFailed for Denied`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            if (folder.canCreateFileIn()) return

            val failure = file.writeText("new", WriteMode.InPlace).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertEquals("old", file.readRaw())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `an InPlace write that fails midway puts the old content back`() {
        val file = (dir / "a.toml").writeRaw("old")

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge, WriteMode.InPlace) }

        result.assertError<FileError.WriteFailed>()
        assertEquals("old", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a write of a new file that fails midway leaves nothing behind`() {
        val file = dir / "new.toml"

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge) }

        result.assertError<FileError.WriteFailed>()
        assertEquals(emptyList<Path>(), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a failed first write through a dangling symlink keeps the link and removes the new file`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .rawSymlinkTo(destination)
            .also { links += it }

        val result = withFileSizeLimit(bytes = 4096) { link.writeBytes(tooLarge) }

        result.assertError<FileError.WriteFailed>()
        assertTrue(link.isLink())
        assertFalse(destination.exists())
    }

    @Test
    fun `readText bounds the read even when the size check passed`() {
        // Files under /proc report a size of 0, like a file that grows after the check.
        val status = Path("/proc/self/status")

        assertEquals(
            Err(FileError.TooLarge(status, limitBytes = 10, atLeastBytes = 11)),
            status.readText(maxBytes = 10),
        )
    }

    @Test
    fun `createDirectories accepts a symlink to a directory and creates through it`() {
        val real = dir / "real"
        createDirectoriesRaw(real)
        val link = (dir / "link")
            .rawSymlinkTo(real)
            .also { links += it }

        assertEquals(Ok(Unit), link.createDirectories())
        assertEquals(Ok(Unit), (link / "sub").createDirectories())
        assertTrue(SystemFileSystem.metadataOrNull(real / "sub")?.isDirectory == true)
    }

    @Test
    fun `readText refuses a FIFO without opening it`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.readText())
    }

    @Test
    fun `writeText refuses a FIFO rather than blocking on a reader that may never come`() {
        // Opening a FIFO for writing waits for a reader, so writing one never returns: a caller above this
        // gets no result and, where it took a lock first, holds it for as long as the process lives.
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.writeText("x = 1"))
    }

    @Test
    fun `readText refuses a device`() {
        assertEquals(Err(FileError.NotRegularFile(device, FileType.CharacterDevice)), device.readText())
    }

    @Test
    fun `writeText refuses a device rather than writing into it`() {
        assertEquals(Err(FileError.NotRegularFile(device, FileType.CharacterDevice)), device.writeText("x = 1"))
    }

    @Test
    fun `fileType tells a FIFO from a character device and a socket`() {
        val fifo = (dir / "pipe").makeFifo()
        val socket = (dir / "sock").makeSocket()

        assertEquals(Ok(FileType.Fifo), fifo.fileType())
        assertEquals(Ok(FileType.CharacterDevice), device.fileType())
        assertEquals(Ok(FileType.Socket), socket.fileType())
    }

    @Test
    fun `listing a socket is NotADirectory naming it a socket`() {
        val socket = (dir / "sock").makeSocket()

        assertEquals(Err(FileError.NotADirectory(socket, FileType.Socket)), socket.list())
    }

    @Test
    fun `isSymlink tells a link from what it points at even when that is missing`() {
        val real = (dir / "real.toml").writeRaw("a")
        val link = (dir / "link.toml")
            .rawSymlinkTo(real)
            .also { links += it }
        val dangling = (dir / "dangling.toml")
            .rawSymlinkTo(dir / "missing.toml")
            .also { links += it }

        assertEquals(Ok(true), link.isSymlink())
        assertEquals(Ok(true), dangling.isSymlink())
        assertEquals(Ok(false), real.isSymlink())
        assertEquals(Ok(false), dir.isSymlink())
    }

    @Test
    fun `isSymlink under a folder that may not be searched is Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            (locked / "a").isSymlink().assertError<FileError.Inaccessible>()
        }
    }

    @Test
    fun `permissions under a folder that may not be searched is Inaccessible as Denied`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "a").writeRaw("a")
        locked.withMode(0) {
            assertEquals(IoCause.Denied, file.permissions().assertError<FileError.Inaccessible>().cause)
        }
    }

    @Test
    fun `permissions follow a symlink to the mode of its target`() {
        val file = (dir / "a").writeRaw("a")
        file.setMode(S_IRUSR or S_IWUSR or S_IXUSR or S_IRGRP)
        val link = (dir / "link")
            .rawSymlinkTo(file)
            .also { links += it }

        assertEquals("rwxr-----", link.permissions().assertSuccess().toString())
    }

    @Test
    fun `readSymlink under a folder that may not be searched is Inaccessible as Denied`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val link = (locked / "a")
            .rawSymlinkTo(dir / "missing.toml")
            .also { links += it }
        locked.withMode(0) {
            assertEquals(IoCause.Denied, link.readSymlink().assertError<FileError.Inaccessible>().cause)
        }
    }

    @Test
    fun `reading a link's content under a folder that may not be searched fails as Denied`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val link = (locked / "a")
            .rawSymlinkTo(dir / "missing.toml")
            .also { links += it }
        locked.withMode(0) {
            assertEquals(IoCause.Denied, runCatching { readLink(link) }.exceptionOrNull()?.ioCause())
        }
    }

    @Test
    fun `a FIFO as the walk root is NotRegularFile`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.walkTopDown())
    }

    @Test
    fun `delete removes a symlink whose target is missing`() {
        val dangling = (dir / "dangling").rawSymlinkTo(dir / "missing")

        assertEquals(Ok(Unit), dangling.delete())
        assertEquals(false, dangling.isLink())
    }

    @Test
    fun `deleteRecursively clears a tree holding a symlink whose target is missing`() {
        val tree = dir / "tree"
        createDirectoriesRaw(tree)
        (tree / "dangling").rawSymlinkTo(dir / "missing")

        assertEquals(Ok(Unit), tree.deleteRecursively())
        assertEquals(false, SystemFileSystem.exists(tree))
    }

    @Test
    fun `a walk yields a FIFO and a dangling symlink as skipped where they stand`() {
        val file = (dir / "a.txt").writeRaw("a")
        val fifo = (dir / "pipe").makeFifo()
        val dangling = (dir / "dangling")
            .rawSymlinkTo(dir / "missing")
            .also { links += it }

        val expected = listOf(
            walkedFile(file, Path("a.txt"), size = 1, viaLink = false),
            WalkEntry.Skipped(dangling, Path("dangling"), WalkSkip.DanglingLink),
            WalkEntry.Skipped(fifo, Path("pipe"), WalkSkip.NotFileOrFolder(FileType.Fifo)),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `a dangling symlink in a folder walked by following links is skipped as a dangling link`() {
        val sub = dir / "sub"
        createDirectoriesRaw(sub)
        val dangling = (sub / "dangling")
            .rawSymlinkTo(dir / "missing")
            .also { links += it }

        val expected = listOf(
            WalkEntry.Folder(sub, Path("sub")),
            WalkEntry.Skipped(dangling, Path("sub") / "dangling", WalkSkip.DanglingLink),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown(Symlinks.Anywhere).assertSuccess().toList())
    }

    @Test
    fun `a walk passes over links leading out of the root by default`() {
        (elsewhere / "inside.txt").writeRaw("i")
        val target = (elsewhere / "target.txt").writeRaw("t")
        (dir / "folder")
            .rawSymlinkTo(elsewhere)
            .also { links += it }
        (dir / "file")
            .rawSymlinkTo(target)
            .also { links += it }

        val expected = listOf(
            WalkEntry.Skipped(dir / "file", Path("file"), WalkSkip.LeadsOutside),
            WalkEntry.Skipped(dir / "folder", Path("folder"), WalkSkip.LeadsOutside),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `a walk that follows links enters a linked folder`() {
        (elsewhere / "inside.txt").writeRaw("i")
        (dir / "folder")
            .rawSymlinkTo(elsewhere)
            .also { links += it }

        val walked = dir.walkTopDown(Symlinks.Anywhere).assertSuccess().walkedFiles()

        assertEquals(listOf(Ok(dir / "folder" / "inside.txt")), walked)
    }

    @Test
    fun `a walk that follows links passes over a link back to a folder it has entered`() {
        val sub = dir / "sub"
        createDirectoriesRaw(sub)
        val file = (sub / "a.txt").writeRaw("a")
        (sub / "up")
            .rawSymlinkTo(dir)
            .also { links += it }

        val expected = listOf(
            WalkEntry.Folder(sub, Path("sub")),
            walkedFile(file, Path("sub") / "a.txt", size = 1, viaLink = false),
            WalkEntry.Skipped(sub / "up", Path("sub") / "up", WalkSkip.AlreadyEntered(firstRelative = null)),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown(Symlinks.Anywhere).assertSuccess().toList())
    }

    @Test
    fun `a directory that may not be read is yielded as a Folder and walks as Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val walked = dir
                .walkTopDown()
                .assertSuccess()
                .toList()
            val denied = FileError.Inaccessible(locked, IoCause.Denied, exception = null)
            val failure = WalkFailure(locked, Path("locked"), denied)
            assertEquals(listOf(Ok(WalkEntry.Folder(locked, Path("locked"))), Err(failure)), walked)
        }
    }

    @Test
    fun `a root that may not be read is Inaccessible before any walking`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val denied = FileError.Inaccessible(locked, IoCause.Denied, exception = null)
            assertEquals(denied, locked.walkTopDown().errorOrNull())
        }
    }

    @Test
    fun `a symlink as the root is entered whether links are followed or not`() {
        val real = dir / "real"
        createDirectoriesRaw(real)
        (real / "a.txt").writeRaw("a")
        val link = (dir / "link")
            .rawSymlinkTo(real)
            .also { links += it }

        assertEquals(listOf(Ok(link / "a.txt")), link.walkTopDown().assertSuccess().walkedFiles())
        assertEquals(listOf(Ok(link / "a.txt")), link.walkTopDown(Symlinks.Anywhere).assertSuccess().walkedFiles())
    }

    @Test
    fun `a symlink loop below the root is skipped as LinkLoop in the walk`() {
        val loop = (dir / "loop")
            .rawSymlinkTo(Path("loop"))
            .also { links += it }

        val walked = dir
            .walkTopDown()
            .assertSuccess()
            .single()

        assertEquals(Ok(WalkEntry.Skipped(loop, Path("loop"), WalkSkip.LinkLoop)), walked)
    }

    @Test
    fun `list of a directory that may not be read is Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        (locked / "app.toml").writeRaw("port = 1")
        // Searchable but not readable: the entries are there, and reading their names is what is refused.
        locked.withMode(S_IXUSR) {
            val error = locked.list().assertError<FileError.Inaccessible>()
            assertEquals(locked, error.path)
        }
    }

    @Test
    fun `deleteRecursively of a folder with entries that may not be listed is Inaccessible for Denied`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        (locked / "app.toml").writeRaw("port = 1")
        locked.withMode(0) {
            val denied = FileError.Inaccessible(locked, IoCause.Denied, exception = null)
            assertEquals(denied, locked.deleteRecursively().errorOrNull())
        }
    }

    @Test
    fun `list of a directory with no permissions at all is Inaccessible for Denied`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val denied = FileError.Inaccessible(locked, IoCause.Denied, exception = null)
            assertEquals(denied, locked.list().errorOrNull())
        }
    }

    @Test
    fun `deleting under a directory that may not be entered is Inaccessible rather than NotFound`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        locked.withMode(0) {
            file.delete().assertError<FileError.Inaccessible>()
        }
    }

    @Test
    fun `a symlink that loops is Inaccessible for Loop`() {
        val first = (dir / "first").also { links += it }
        val second = (dir / "second").also { links += it }
        first.rawSymlinkTo(second)
        second.rawSymlinkTo(first)

        assertEquals(IoCause.Loop, first.readText().assertError<FileError.Inaccessible>().cause)
    }

    @Test
    fun `a file under a directory that may not be entered is Inaccessible for Denied rather than NotFound`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        locked.withMode(0) {
            assertEquals(IoCause.Denied, file.readText().assertError<FileError.Inaccessible>().cause)
        }
    }

    @Test
    fun `a write whose restore also fails reports RestoreFailed and keeps the backup`() {
        val file = (dir / "a.toml").writeRaw("old")

        val result = withFileSizeLimitLocking(file, bytes = 4096, mode = S_IRUSR) {
            file.writeBytes(tooLarge, WriteMode.InPlace)
        }

        try {
            // A runner that may write the file anyway, such as root, restores the backup and never gets here.
            if (file.canOpenForWriting()) return

            val error = result.assertError<FileError.RestoreFailed>()
            val backup = assertNotNull(error.backup)
            assertEquals("old", backup.readRaw())
            // The file keeps what the failed write left in it, so the backup is the only intact copy.
            assertNotEquals("old", file.readRaw())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `a RestoreFailed still names a backup that could not be looked up`() {
        val file = (dir / "a.toml").writeRaw("old")

        // Without the search bit the backup cannot be looked up at all, which is not the same as being gone.
        val result = withFileSizeLimitLocking(dir, bytes = 4096, mode = S_IRUSR) {
            file.writeBytes(tooLarge, WriteMode.InPlace)
        }

        dir.withModeRestored {
            assertNotNull(result.assertError<FileError.RestoreFailed>().backup)
        }
    }

    @Test
    fun `a backup carries the permissions of the file it copies`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)

        // Only a failed undo leaves the copy to look at: an ordinary write disposes of it before returning,
        // and that is the window a copy wider than the file would be readable in.
        val result = withFileSizeLimitLocking(file, bytes = 4096, mode = S_IRUSR) {
            file.writeBytes(tooLarge, WriteMode.InPlace)
        }

        try {
            // A runner that may write the file anyway, such as root, restores the backup and never gets here.
            if (file.canOpenForWriting()) return

            val backup = result.assertError<FileError.RestoreFailed>().backup

            assertEquals(S_IRUSR or S_IWUSR, assertNotNull(backup).mode())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `an InPlace restore leaves the file the permissions it had`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)

        // The restore writes into the file itself, which keeps its own permissions throughout.
        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge, WriteMode.InPlace) }

        result.assertError<FileError.WriteFailed>()
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
        assertEquals("old", file.readRaw())
    }

    @Test
    fun `a RestoreFailed names no backup when the file was new`() {
        val file = dir / "new.toml"

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeBytes(tooLarge, WriteMode.InPlace) }

        dir.withModeRestored {
            assertNull(result.assertError<FileError.RestoreFailed>().backup)
        }
    }

    @Test
    fun `a RestoreFailed reports why the undo failed and not why the write did`() {
        val file = (dir / "a.toml").writeRaw("old")
        // The same write fails the same way in both runs. Only the second one cannot put the backup back, so
        // any difference in what is reported is the undo's doing.
        val failedWrite = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge, WriteMode.InPlace) }
        val writeMessage = failedWrite.assertError<FileError.WriteFailed>().exception?.message

        val result = withFileSizeLimitLocking(file, bytes = 4096, mode = S_IRUSR) {
            file.writeBytes(tooLarge, WriteMode.InPlace)
        }

        try {
            // A runner that may write the file anyway, such as root, restores the backup and never gets here.
            if (file.canOpenForWriting()) return

            val error = result.assertError<FileError.RestoreFailed>()
            assertNotEquals(writeMessage, error.exception?.message)
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `leftovers of a symlink that loops are Inaccessible`() {
        val loop = (dir / "loop.toml")
            .rawSymlinkTo(Path("loop.toml"))
            .also { links += it }

        assertEquals(loop, loop.leftoverCopies<Leftover.Backup>().assertError<FileError.Inaccessible>().path)
        assertEquals(loop, loop.leftoverCopies<Leftover.Temp>().assertError<FileError.Inaccessible>().path)
    }

    @Test
    fun `leftovers of a file in a folder that may be read but not searched are Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        locked.setMode(S_IRUSR)
        try {
            // A runner that may look the file up anyway, such as root, has nothing to observe.
            if (file.canOpenForReading()) return

            file.leftoverCopies<Leftover.Backup>().assertError<FileError.Inaccessible>()
            file.leftoverCopies<Leftover.Temp>().assertError<FileError.Inaccessible>()
        } finally {
            locked.setMode(S_IRWXU)
        }
    }

    @Test
    fun `moveTo onto a symlink replaces the link and leaves the file it pointed at alone`() {
        val real = (dir / "real.toml").writeRaw("theirs")
        val link = (dir / "link.toml").rawSymlinkTo(real)
        val copy = (dir / "copy.toml").writeRaw("mine")

        assertEquals(Ok(Unit), copy.moveTo(link))

        assertFalse(link.isLink())
        assertEquals("mine", link.readRaw())
        assertEquals("theirs", real.readRaw())
    }

    @Test
    fun `moveTo of a file onto a symlink to a folder replaces the link and leaves the folder alone`() {
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        val link = (dir / "link").rawSymlinkTo(folder)
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), file.moveTo(link))

        assertFalse(link.isLink())
        assertEquals("a", link.readRaw())
        assertEquals(emptyList<Path>(), SystemFileSystem.list(folder).toList())
    }

    @Test
    fun `leftovers finds the backup of a file named without a folder`() {
        val previous = currentDirectory()
        changeDirectory(dir)
        try {
            Path("rel.toml").writeRaw("a = 1")
            Path(".rel.toml.$A_WRITE_ID.bak").writeRaw("a = 0")

            val found = Path("rel.toml").leftoverCopies<Leftover.Backup>().assertSuccess()

            assertEquals(listOf(".rel.toml.$A_WRITE_ID.bak"), found.map { it.name })
        } finally {
            changeDirectory(previous)
        }
    }

    @Test
    fun `createDirectories below a folder that may not be searched is Inaccessible naming the path asked for`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val asked = locked / "app" / "deeper"

            assertEquals(asked, asked.createDirectories().assertError<FileError.Inaccessible>().path)
        }
    }

    @Test
    fun `a write under a folder that may not be searched is Inaccessible naming the folder`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val result = (locked / "app" / "settings.toml").writeText("a = 1")

            assertEquals(locked / "app", result.assertError<FileError.Inaccessible>().path)
        }
    }

    @Test
    fun `a write through a symlink whose target folder is missing creates that folder`() {
        val link = (dir / "link.toml")
            .rawSymlinkTo(dir / "absent" / "app.toml")
            .also { links += it }

        assertEquals(Ok(WriteWay.Renamed), link.writeText("a = 1").way())

        assertTrue(link.isLink())
        assertEquals("a = 1", (dir / "absent" / "app.toml").readRaw())
    }

    @Test
    fun `createDirectories takes a relative path from the working directory`() {
        val previous = currentDirectory()
        changeDirectory(dir)
        try {
            assertEquals(Ok(Unit), Path("nested", "deeper").createDirectories())
            assertTrue(SystemFileSystem.metadataOrNull(dir / "nested" / "deeper")?.isDirectory == true)
        } finally {
            changeDirectory(previous)
        }
    }

    @Test
    fun `deleteRecursively removes a symlink without following it`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val tree = dir / "tree"
        createDirectoriesRaw(tree)
        (tree / "link")
            .rawSymlinkTo(outside)
            .also { links += it }

        assertEquals(Ok(Unit), tree.deleteRecursively())
        assertFalse(tree.exists())
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `delete removes a link to a folder with entries and leaves the folder whole`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val link = (dir / "link")
            .rawSymlinkTo(outside)
            .also { links += it }

        assertEquals(Ok(Unit), link.delete())
        assertFalse(link.isLink())
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `deleteRecursively removes a link to a folder with entries and leaves the folder whole`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val link = (dir / "link")
            .rawSymlinkTo(outside)
            .also { links += it }

        assertEquals(Ok(Unit), link.deleteRecursively())
        assertFalse(link.isLink())
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `deleteRecursively on a link it may not remove fails without entering the folder it points at`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val link = (folder / "link")
            .rawSymlinkTo(outside)
            .also { links += it }
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, removes the link and leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            link.deleteRecursively().assertError<FileError.WriteFailed>()
            assertEquals("keep", kept.readRaw())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `an Auto write by rename keeps the group of the file`() {
        val group = otherGroupOrNull() ?: return
        val file = (dir / "shared.toml").writeRaw("old")
        file.setGroup(group)
        val before = file.inode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Auto).way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.inode())
        assertEquals(group, file.group())
    }

    @Test
    fun `deleteRecursively reports the removal's own failure when the path is no directory`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, removes the file and leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            val error = file.deleteRecursively().assertError<FileError.WriteFailed>()
            assertEquals(file, error.path)
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `modifiedAt reads the seconds and fraction stat keeps`() {
        val file = (dir / "stamped.txt").writeRaw("x")
        file.setModifiedTime(epochSeconds = 1_709_210_096, microseconds = 123_456)

        assertEquals(Instant.fromEpochSeconds(1_709_210_096, 123_456_000), file.modifiedAt().assertSuccess())
    }

    @Test
    fun `realPath resolves every link on the way`() {
        val real = (dir / "real.toml").writeRaw("a")
        val second = (dir / "second.toml")
            .rawSymlinkTo(real)
            .also { links += it }
        val first = (dir / "first.toml")
            .rawSymlinkTo(Path("second.toml"))
            .also { links += it }

        assertEquals(Ok(SystemFileSystem.resolve(real)), first.realPath())
        assertTrue(second.isLink())
    }

    @Test
    fun `realPath under a folder that may not be searched is Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        (locked / "a.toml").writeRaw("a")
        locked.withMode(0) {
            (locked / "a.toml").realPath().assertError<FileError.Inaccessible>()
        }
    }

    @Test
    fun `readText of a file this user may not read is Inaccessible for Denied`() =
        assertUnreadableRefused { it.readText() }

    @Test
    fun `readBytes of a file this user may not read is Inaccessible for Denied`() =
        assertUnreadableRefused { it.readBytes() }

    @Test
    fun `copyTo from a file this user may not read fails with the system's message`() =
        assertUnreadableRefused { it.copyTo(dir / "copy.toml") }

    /** Runs [read] on a file of mode 000 and checks it is Inaccessible for Denied, naming that file. */
    private fun assertUnreadableRefused(read: (Path) -> Result<Any?, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(0)
        try {
            // A runner that may read it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForReading()) return

            val failure = read(file).assertError<FileError.Inaccessible>()

            assertEquals(file, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            assertEquals("$file: Permission denied", failure.exception?.message)
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `the copy a write makes of a read-only file holds its content and ends read-only`() {
        val readOnly = S_IRUSR or S_IRGRP or S_IROTH
        val file = (dir / "a.toml").writeRaw("old")
        val copy = dir / ".a.toml.$A_WRITE_ID.bak"
        file.setMode(readOnly)

        copyFile(from = file, to = copy).close()

        assertEquals("old", copy.readRaw())
        assertEquals(readOnly, copy.mode())
    }

    @Test
    fun `a restore puts the backup back even when the backup's own mode would not let it be read`() {
        val file = (dir / "a.toml").writeRaw("old")
        val backupPath = dir / ".a.toml.$A_WRITE_ID.bak"
        val backup = copyFile(from = file, to = backupPath)
        file.writeRaw("half")
        backupPath.setMode(S_IWUSR)

        assertNull(undoFailure(Undo.Restore(backupPath, backup), PathFolder(file.parent), file.name))

        assertEquals("old", file.readRaw())
        assertFalse(SystemFileSystem.exists(backupPath))
    }

    @Test
    fun `a new file written by Auto with ownerOnly is 0600`() =
        assertCreatedOwnerOnly { it.writeText("new", WriteMode.Auto, ownerOnly = true) }

    @Test
    fun `a new file written by Atomic with ownerOnly is 0600`() =
        assertCreatedOwnerOnly { it.writeText("new", WriteMode.Atomic, ownerOnly = true) }

    @Test
    fun `a new file written by Replace with ownerOnly is 0600`() =
        assertCreatedOwnerOnly { it.writeBytes("new".encodeToByteArray(), WriteMode.Replace, ownerOnly = true) }

    @Test
    fun `a new file written InPlace with ownerOnly is 0600`() =
        assertCreatedOwnerOnly { it.writeText("new", WriteMode.InPlace, ownerOnly = true) }

    @Test
    fun `a new file made by copyTo with ownerOnly is 0600`() =
        assertCreatedOwnerOnly { (dir / "source.toml").writeRaw("new").copyTo(it, ownerOnly = true) }

    @Test
    fun `an existing 0644 file written by Auto with ownerOnly ends 0600`() =
        assertExistingNarrowed { it.writeText("new", WriteMode.Auto, ownerOnly = true) }

    @Test
    fun `an existing 0644 file written by Atomic with ownerOnly ends 0600`() =
        assertExistingNarrowed { it.writeText("new", WriteMode.Atomic, ownerOnly = true) }

    @Test
    fun `an existing 0644 file written by Replace with ownerOnly ends 0600`() =
        assertExistingNarrowed { it.writeText("new", WriteMode.Replace, ownerOnly = true) }

    @Test
    fun `an existing 0644 file written InPlace with ownerOnly ends 0600`() =
        assertExistingNarrowed { it.writeText("new", WriteMode.InPlace, ownerOnly = true) }

    @Test
    fun `an existing 0644 file copied onto with ownerOnly ends 0600`() =
        assertExistingNarrowed { (dir / "source.toml").writeRaw("new").copyTo(it, ownerOnly = true) }

    /** Runs [write] onto a missing file under umask 022 and checks the file it made is `0600` and holds `new`. */
    private fun assertCreatedOwnerOnly(write: (Path) -> Result<Copied, FileError>) {
        val file = dir / "new.toml"
        val plain = dir / "plain.toml"

        val written = withUmask(UMASK_022) {
            assertEquals(Ok(WriteWay.Renamed), plain.writeText("plain").way())
            write(file)
        }

        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH, plain.mode())
        written.assertSuccess()
        assertEquals("new", file.readRaw())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    /** Runs [write] onto an existing `0644` file and checks it holds `new` and is `0600`. */
    private fun assertExistingNarrowed(write: (Path) -> Result<Copied, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)

        withUmask(UMASK_022) { write(file) }.assertSuccess()

        assertEquals("new", file.readRaw())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `a new file written with executable under umask 022 is 0755 and 0700 with ownerOnly`() {
        val written = withUmask(UMASK_022) {
            listOf(
                (dir / "auto.sh").writeText("x", executable = true),
                (dir / "inplace.sh").writeText("x", WriteMode.InPlace, executable = true),
                (dir / "source.txt").writeRaw("x").copyTo(dir / "copied.sh", executable = true),
                (dir / "owner.sh").writeText("x", ownerOnly = true, executable = true),
            )
        }

        val all = S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH
        val modes = listOf("auto.sh", "inplace.sh", "copied.sh", "owner.sh").map { (dir / it).mode() }

        val expected = listOf(WriteWay.Renamed, WriteWay.InPlace, WriteWay.Renamed, WriteWay.Renamed)
        assertEquals(expected, written.map { it.way().assertSuccess() })
        assertEquals(listOf(all, all, all, S_IRWXU), modes)
    }

    @Test
    fun `executable gives a rename write's temp and an in-place write's file their execute bits before any content`() {
        val renamed = (dir / "renamed.sh").writeRaw("old")
        val inPlace = (dir / "inplace.sh").writeRaw("old")
        renamed.setMode(S_IRUSR or S_IWUSR or S_IRGRP)
        inPlace.setMode(S_IRUSR or S_IWUSR or S_IRGRP)
        var tempMode: Int? = null
        var fileMode: Int? = null

        renamed.write(WriteMode.Replace, executable = true) { sink ->
            tempMode = renamed
                .leftoverCopies<Leftover.Temp>()
                .assertSuccess()
                .single()
                .mode()
            sink.writeString("new")
        }.assertSuccess()
        inPlace.write(WriteMode.InPlace, executable = true) { sink ->
            fileMode = inPlace.mode()
            sink.writeString("new")
        }.assertSuccess()

        val readable = S_IRWXU or S_IRGRP or S_IXGRP
        assertEquals(readable, tempMode)
        assertEquals(readable, fileMode)
        assertEquals(readable, renamed.mode())
    }

    @Test
    fun `an in-place write with executable gives the file itself the bits so every hard link sees them`() {
        val file = (dir / "run.sh").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)
        val other = (dir / "other.sh").rawHardLinkTo(file)

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", executable = true).way())

        assertEquals(S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH, other.mode())
        assertEquals("new", other.readRaw())
    }

    @Test
    fun `a rename write with ownerOnly makes its temp 0600 before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)
        var tempMode: Int? = null

        val written = withUmask(UMASK_022) {
            file.write(WriteMode.Replace, ownerOnly = true) { sink ->
                tempMode = file
                    .leftoverCopies<Leftover.Temp>()
                    .assertSuccess()
                    .single()
                    .mode()
                sink.writeString("new")
            }
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals(S_IRUSR or S_IWUSR, tempMode)
    }

    @Test
    fun `an in-place write with ownerOnly narrows the file and its backup to 0600 before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)
        var fileMode: Int? = null
        var backupMode: Int? = null

        val written = withUmask(UMASK_022) {
            file.write(WriteMode.InPlace, ownerOnly = true) { sink ->
                fileMode = file.mode()
                backupMode = file
                    .leftoverCopies<Leftover.Backup>()
                    .assertSuccess()
                    .single()
                    .mode()
                sink.writeString("new")
            }
        }

        assertEquals(Ok(WriteWay.InPlace), written.way())
        assertEquals(S_IRUSR or S_IWUSR, fileMode)
        assertEquals(S_IRUSR or S_IWUSR, backupMode)
    }

    @Test
    fun `a new file written without ownerOnly follows the umask`() {
        val file = dir / "new.toml"

        val written = withUmask(UMASK_022) { file.writeText("new") }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH, file.mode())
    }

    @Test
    fun `copyTo with keepAttributes gives a new target the source's 600 mode`() = assertModeKept(S_IRUSR or S_IWUSR)

    @Test
    fun `copyTo with keepAttributes gives a new target the source's 755 mode`() =
        assertModeKept(S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH)

    /** Copies a source of [mode] to a new target keeping attributes, under umask 022, and checks it has [mode]. */
    private fun assertModeKept(mode: Int) {
        val source = (dir / "a.sh").writeRaw("content")
        source.setMode(mode)
        val target = dir / "b.sh"

        val written = withUmask(UMASK_022) { source.copyTo(target, keepAttributes = true) }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals("content", target.readRaw())
        assertEquals(mode, target.mode())
    }

    @Test
    fun `an InPlace copyTo with keepAttributes gives the target the source's mode and keeps the target itself`() {
        val source = (dir / "a.sh").writeRaw("new")
        source.setMode(S_IRWXU or S_IRGRP or S_IXGRP)
        val target = (dir / "b.sh").writeRaw("old")
        val before = target.inode()

        assertEquals(Ok(WriteWay.InPlace), source.copyTo(target, WriteMode.InPlace, keepAttributes = true).way())

        assertEquals("new", target.readRaw())
        assertEquals(before, target.inode())
        assertEquals(S_IRWXU or S_IRGRP or S_IXGRP, target.mode())
    }

    @Test
    fun `copyTo with keepAttributes gives the target the source's modification time by rename`() =
        assertModifiedTimeKept(WriteMode.Auto, WriteWay.Renamed)

    @Test
    fun `copyTo with keepAttributes gives the target the source's modification time in place`() =
        assertModifiedTimeKept(WriteMode.InPlace, WriteWay.InPlace)

    /** Copies an old source onto an existing target in [mode] keeping attributes, and checks the times match. */
    private fun assertModifiedTimeKept(
        mode: WriteMode,
        written: WriteWay,
    ) {
        val source = (dir / "a.txt").writeRaw("new")
        source.setModifiedTime(epochSeconds = 981_173_106, microseconds = 123_456)
        val target = (dir / "b.txt").writeRaw("old")

        assertEquals(Ok(written), source.copyTo(target, mode, keepAttributes = true).way())

        assertEquals("new", target.readRaw())
        assertEquals(Ok(Instant.fromEpochSeconds(981_173_106, 123_456_000)), target.modifiedAt())
    }

    @Test
    fun `copyTo with keepAttributes and ownerOnly leaves the target 0600 whatever the source's mode`() {
        val source = (dir / "a.sh").writeRaw("content")
        source.setMode(S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH)
        val target = dir / "b.sh"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target, ownerOnly = true, keepAttributes = true).way())

        assertEquals(S_IRUSR or S_IWUSR, target.mode())
    }

    @Test
    fun `copyTo without keepAttributes gives a new target the mode and time any new file gets`() {
        val source = (dir / "a.sh").writeRaw("content")
        source.setMode(S_IRWXU)
        source.setModifiedTime(epochSeconds = 981_173_106, microseconds = 0)
        val target = dir / "b.sh"

        val written = withUmask(UMASK_022) { source.copyTo(target) }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH, target.mode())
        assertNotEquals(source.modifiedAt(), target.modifiedAt())
    }

    @Test
    fun `copyTo onto itself with keepAttributes changes neither mode nor time`() {
        val file = (dir / "a.txt").writeRaw("content")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP)
        file.setModifiedTime(epochSeconds = 981_173_106, microseconds = 0)

        assertIs<Copied.Unchanged>(file.copyTo(file, keepAttributes = true).assertSuccess())

        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP, file.mode())
        assertEquals(Ok(Instant.fromEpochSeconds(981_173_106)), file.modifiedAt())
    }

    @Test
    fun `a rename write keeping attributes gives its temp the kept mode before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        var tempMode: Int? = null
        val kept = KeptAttributes(permissions = S_IRWXU or S_IRGRP or S_IXGRP, Instant.fromEpochSeconds(981_173_106))

        val written = withUmask(UMASK_022) {
            file.write(WriteMode.Replace, kept = kept) { sink ->
                tempMode = file
                    .leftoverCopies<Leftover.Temp>()
                    .assertSuccess()
                    .single()
                    .mode()
                sink.writeString("new")
            }
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals(S_IRWXU or S_IRGRP or S_IXGRP, tempMode)
    }

    @Test
    fun `an in-place write keeping attributes gives the file the kept mode before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)
        var fileMode: Int? = null
        var backupMode: Int? = null
        val kept = KeptAttributes(permissions = S_IRWXU or S_IRGRP or S_IXGRP, Instant.fromEpochSeconds(981_173_106))

        val written = file.write(WriteMode.InPlace, kept = kept) { sink ->
            fileMode = file.mode()
            backupMode = file
                .leftoverCopies<Leftover.Backup>()
                .assertSuccess()
                .single()
                .mode()
            sink.writeString("new")
        }

        assertEquals(Ok(WriteWay.InPlace), written.way())
        assertEquals(S_IRWXU or S_IRGRP or S_IXGRP, fileMode)
        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH, backupMode)
    }

    @Test
    fun `an in-place write whose backup copy fails leaves the file intact and no backup`() {
        val file = dir / "big.toml"
        file.writeBytes(tooLarge, WriteMode.InPlace).assertSuccess()

        val result = withFileSizeLimit(bytes = 4096) { file.writeText("new", WriteMode.InPlace) }

        result.assertError<FileError.WriteFailed>()
        assertEquals(tooLarge.size.toLong(), SystemFileSystem.metadataOrNull(file)?.size)
        assertEquals(Ok(emptyList()), file.leftovers())
    }

    @Test
    fun `an in-place write whose backup copy fails and cannot clean up leaves only a partial backup`() {
        val file = dir / "big.toml"
        file.writeBytes(tooLarge, WriteMode.InPlace).assertSuccess()

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeText("new", WriteMode.InPlace) }

        dir.withModeRestored {
            result.assertError<FileError.WriteFailed>()
            assertEquals(tooLarge.size.toLong(), SystemFileSystem.metadataOrNull(file)?.size)
            val leftover = file.leftovers().assertSuccess().single()
            assertIs<Leftover.PartialBackup>(leftover)
            assertTrue(leftover.copy.name.endsWith(".bak.part"))
        }
    }

    @Test
    fun `leftovers through a symlink are typed and name the file the link points at`() {
        val real = (dir / "real.toml").writeRaw("a = 1")
        val link = (dir / "link.toml")
            .rawSymlinkTo(real)
            .also { links += it }
        val backup = (dir / ".real.toml.$A_WRITE_ID.bak").writeRaw("a = 0")
        val part = (dir / ".real.toml.$A_WRITE_ID.bak.part").writeRaw("a")

        val expected = listOf(
            Leftover.Backup(real, backup, A_WRITE_START, via = link),
            Leftover.PartialBackup(real, part, A_WRITE_START, via = link),
        )
        assertEquals(Ok(expected), link.leftovers())
    }

    @Test
    fun `leftoversIn asks no link to a folder for leftovers so one in a folder that may not be listed is no error`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked / "inner")
        val scanned = dir / "scanned"
        createDirectoriesRaw(scanned)
        (scanned / "inner")
            .rawSymlinkTo(locked / "inner")
            .also { links += it }
        locked.withMode(S_IXUSR) {
            assertEquals(Ok(LeftoverScan.EMPTY), scanned.leftoversIn())
        }
    }

    @Test
    fun `canonicalPath of a path below a folder that may not be searched is Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        locked.withMode(0) {
            val failure = (locked / "a" / "b").canonicalPath().assertError<FileError.Inaccessible>()
            assertEquals(IoCause.Denied, failure.cause)
        }
    }

    @Test
    fun `canonicalPath of a path below a symlink loop is Inaccessible as a loop`() {
        val loop = (dir / "loop")
            .rawSymlinkTo(Path("loop"))
            .also { links += it }

        assertEquals(IoCause.Loop, (loop / "a").canonicalPath().assertError<FileError.Inaccessible>().cause)
    }

    @Test
    fun `canonicalPath resolves a relative path against the working directory`() {
        val previous = Path(".").realPath().assertSuccess()
        changeDirectory(dir)
        try {
            assertEquals(Ok(Path(dir.realPath().assertSuccess(), "sub", "a.txt")), Path("sub/a.txt").canonicalPath())
        } finally {
            changeDirectory(previous)
        }
    }
}
