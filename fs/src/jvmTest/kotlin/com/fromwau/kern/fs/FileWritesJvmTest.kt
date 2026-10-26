package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
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
import org.junit.jupiter.api.Assumptions.assumeFalse

class FileWritesJvmTest {
    private val dir = newTempDir()
    private val link = dir / "link.toml"

    @AfterTest
    fun cleanUp() {
        Files.deleteIfExists(Paths.get(link.toString()))
        dir.deleteTree()
    }

    @Test
    fun `an Auto write through a symlink replaces the target by rename and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("old")
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))
        val before = real.inode()

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Auto).way())

        assertEquals(Paths.get(real.toString()), Files.readSymbolicLink(Paths.get(link.toString())))
        assertEquals("new", real.readRaw())
        assertNotEquals(before, real.inode())
    }

    @Test
    fun `an Auto write replaces a lone file by rename and says Renamed keeping its permissions`() {
        val file = (dir / "a.toml").writeRaw("old")
        val owned = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(Paths.get(file.toString()), owned)
        val before = file.inode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Auto).way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.inode())
        assertEquals(owned, Files.getPosixFilePermissions(Paths.get(file.toString())))
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an InPlace write keeps the file itself`() {
        val file = (dir / "a.toml").writeRaw("old")
        val before = file.inode()

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", WriteMode.InPlace).way())

        assertEquals("new", file.readRaw())
        assertEquals(before, file.inode())
    }

    @Test
    fun `an InPlace copyTo onto a symlink to itself keeps the content`() {
        val file = (dir / "a.toml").writeRaw("content")
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(file.toString()))

        assertIs<Copied.Unchanged>(file.copyTo(link, WriteMode.InPlace).assertSuccess())

        assertEquals("content", file.readRaw())
    }

    @Test
    fun `a restore puts the old content back into the file so its other hard links see it too`() {
        val file = (dir / "a.toml").writeRaw("old")
        val other = (dir / "b.toml").rawHardLinkTo(file)
        val backupPath = dir / ".a.toml.$A_WRITE_ID.bak"
        val backup = copyFile(from = file, to = backupPath)
        file.writeRaw("half")

        assertNull(undoFailure(Undo.Restore(backupPath, backup), PathFolder(file.parent), file.name))

        assertEquals(file.inode(), other.inode())
        assertEquals("old", other.readRaw())
        assertEquals(setOf(file, other), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `a Replace write that cannot keep the group takes the group's permissions away`() {
        val file = (dir / "shared.toml").writeRaw("old")
        val nio = Paths.get(file.toString())
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-rw----"))

        val written = file.write(WriteMode.Replace, keepGroup = { _, _ -> false }) {
            it.write("new".encodeToByteArray())
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals("new", file.readRaw())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(nio))
    }

    @Test
    fun `an Atomic write through a dangling symlink creates its target and keeps the link`() {
        val destination = dir / "dest.toml"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(destination.toString()))

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Atomic).way())

        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("new", destination.readRaw())
    }

    @Test
    fun `a write through a dangling relative symlink lands beside the link with the folders it needs`() {
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get("sub", "dest.toml"))

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Auto).way())

        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("new", (dir / "sub" / "dest.toml").readRaw())
    }

    @Test
    fun `a write through a chain of links ending in a missing file creates that file and keeps every link`() {
        val second = Paths.get((dir / "second.toml").toString())
        val destination = dir / "deep" / "dest.toml"
        Files.createSymbolicLink(second, Paths.get(destination.toString()))
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get("second.toml"))
        try {
            assertEquals(Ok(WriteWay.Renamed), link.writeText("new", WriteMode.Replace).way())

            assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
            assertTrue(Files.isSymbolicLink(second))
            assertEquals("new", destination.readRaw())
        } finally {
            Files.deleteIfExists(second)
        }
    }

    @Test
    fun `deleting under a directory that may not be entered is Inaccessible rather than NotFound`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "a.toml").writeRaw("old")
        shutOut(locked)
        try {
            assumeFalse(canLookUp(file), "this user looks up paths in folders it may not search")

            file.delete().assertError<FileError.Inaccessible>()
        } finally {
            restore(locked)
        }
    }

    @Test
    fun `leftovers of a symlink that loops are Inaccessible`() {
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(link.name))

        assertEquals(link, link.leftoverCopies<Leftover.Backup>().assertError<FileError.Inaccessible>().path)
        assertEquals(link, link.leftoverCopies<Leftover.Temp>().assertError<FileError.Inaccessible>().path)
    }

    @Test
    fun `leftovers of a file in a folder that may be read but not searched are Inaccessible`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        val file = (locked / "a.toml").writeRaw("old")
        File(locked.toString()).setExecutable(false)
        try {
            assumeFalse(canLookUp(file), "this user looks up paths in folders it may not search")

            file.leftoverCopies<Leftover.Backup>().assertError<FileError.Inaccessible>()
            file.leftoverCopies<Leftover.Temp>().assertError<FileError.Inaccessible>()
        } finally {
            restore(locked)
        }
    }

    @Test
    fun `moveTo onto a symlink replaces the link and leaves the file it pointed at alone`() {
        val real = (dir / "real.toml").writeRaw("theirs")
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))
        val copy = (dir / "copy.toml").writeRaw("mine")

        assertEquals(Ok(Unit), copy.moveTo(link))

        assertFalse(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("mine", link.readRaw())
        assertEquals("theirs", real.readRaw())
    }

    @Test
    fun `createDirectories below a folder that may not be searched is Inaccessible naming the path asked for`() {
        val locked = dir / "locked"
        createDirectoriesRaw(locked)
        shutOut(locked)
        try {
            assumeFalse(canLookUp(locked / "app"), "this user looks up paths in folders it may not search")

            val asked = locked / "app" / "deeper"

            assertEquals(asked, asked.createDirectories().assertError<FileError.Inaccessible>().path)
        } finally {
            restore(locked)
        }
    }

    @Test
    fun `createDirectories in a folder that may not be written is WriteFailed for Denied`() {
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = (readOnly / "app").createDirectories().assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `moveTo into a folder that may not be written is WriteFailed for Denied`() {
        val from = (dir / "a.toml").writeRaw("a")
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = from.moveTo(readOnly / "b.toml").assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `moveTo of a symlink to a file onto a folder is NotRegularFile naming the folder and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("kept")
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
    }

    @Test
    fun `moveTo of a symlink to a folder onto a folder with entries is NotRegularFile naming the folder`() {
        val real = dir / "real"
        createDirectoriesRaw(real)
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
    }

    @Test
    fun `moveTo of a symlink to a folder onto an empty folder is NotRegularFile naming the folder`() {
        val real = dir / "real"
        createDirectoriesRaw(real)
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))
        val folder = dir / "folder"
        createDirectoriesRaw(folder)

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
        assertTrue(Files.isDirectory(Paths.get(folder.toString())))
    }

    @Test
    fun `moveTo of a dangling symlink onto a folder with entries is NotRegularFile naming the folder`() {
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get((dir / "missing.toml").toString()))
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        (folder / "entry").writeRaw("a")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), link.moveTo(folder))
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
    }

    @Test
    fun `moveTo of a dangling symlink into a folder that may not be written is WriteFailed`() {
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get((dir / "missing.toml").toString()))
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            link.moveTo(readOnly / "b.toml").assertError<FileError.WriteFailed>()
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `a new file written into a folder that may not be written is WriteFailed for Denied`() {
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = (readOnly / "a.toml").writeText("a").assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
            val temp = assertIs<FailedAt.Temp>(failure.at).path
            assertEquals(readOnly, temp.parent)
            assertTrue(temp.name.startsWith(".a.toml.") && temp.name.endsWith(".tmp"))
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `a write through a link onto a read-only file names the file the link leads to as where it was refused`() {
        val file = (dir / "theirs.toml").writeRaw("old")
        val link = dir / "a.toml"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(file.toString()))
        File(file.toString()).setWritable(false)
        try {
            assumeFalse(Files.isWritable(Paths.get(file.toString())), "this user writes read-only files")

            val failure = link.writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()

            assertEquals(link, failure.path)
            assertEquals(FailedAt.LinkTarget(file), failure.at)
        } finally {
            File(file.toString()).setWritable(true)
            Files.delete(Paths.get(link.toString()))
        }
    }

    @Test
    fun `a write through a link into a folder that may not be written names the link and the temp it was refused`() {
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        val target = (readOnly / "theirs.toml").writeRaw("old")
        val link = dir / "a.toml"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(target.toString()))
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = link.writeText("new", WriteMode.Atomic).assertError<FileError.WriteFailed>()

            assertEquals(link, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            val temp = assertIs<FailedAt.Temp>(failure.at).path
            assertEquals(readOnly, temp.parent)
            assertTrue(temp.name.startsWith(".theirs.toml.") && temp.name.endsWith(".tmp"))
            assertEquals("old", target.readRaw())
        } finally {
            File(readOnly.toString()).setWritable(true)
            Files.delete(Paths.get(link.toString()))
        }
    }

    @Test
    fun `a new file written InPlace into a folder that may not be written is WriteFailed for Denied`() {
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = (readOnly / "a.toml").writeText("a", WriteMode.InPlace).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `an InPlace write in a folder that may not be written is WriteFailed for Denied`() {
        val readOnly = dir / "readonly"
        createDirectoriesRaw(readOnly)
        val file = (readOnly / "a.toml").writeRaw("old")
        File(readOnly.toString()).setWritable(false)
        try {
            assumeFalse(
                File(readOnly.toString(), "probe").mkdir(),
                "this user writes into folders without write permission",
            )

            val failure = file.writeText("new", WriteMode.InPlace).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertEquals("old", file.readRaw())
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `a cause is the one the exception's type stands for`() {
        assertEquals(IoCause.Denied, AccessDeniedException("a").ioCause())
        assertEquals(IoCause.NotFound, NoSuchFileException("a", "b", null).ioCause())
        assertEquals(IoCause.Loop, FileSystemLoopException("a").ioCause())
        assertEquals(IoCause.ReadOnly, ReadOnlyFileSystemException().ioCause())
    }

    @Test
    fun `a typed exception kotlinx-io wraps in its own still gives its cause`() {
        assertEquals(IoCause.ReadOnly, IOException("Move failed", ReadOnlyFileSystemException()).ioCause())
    }

    @Test
    fun `a cause is never read from a message`() {
        assertEquals(IoCause.Other, IOException("Permission denied").ioCause())
        assertEquals(IoCause.Other, FileSystemException("a", null, "No space left on device").ioCause())
        assertEquals(IoCause.Other, FileNotFoundException("a (Permission denied)").ioCause())
        assertEquals(IoCause.Other, DirectoryNotEmptyException("a").ioCause())
    }

    @Test
    fun `a symlink loop the JDK reports by its exception type is a denied lookup for Loop`() {
        val loop = FileSystemLoopException("/a/loop")

        assertEquals(PathProbe.Denied(loop), loop.asProbe())
        assertEquals(IoCause.Loop, loop.ioCause())
    }

    @Test
    fun `a symlink pointing at itself is a Loop carrying the JDK's own exception and a walk passes over it`() {
        val notebook = dir / "linky"
        createDirectoriesRaw(notebook)
        val loop = Paths.get((notebook / "loop").toString())
        Files.createSymbolicLink(loop, Paths.get("loop"))
        try {
            val failure = (notebook / "loop").readText().assertError<FileError.Inaccessible>()
            val walked = notebook
                .walkTopDown()
                .assertSuccess()
                .single()

            // JDK 17 and 25 raise a bare FileSystemException for the loop, whose type says nothing more.
            assertEquals(IoCause.Loop, failure.cause)
            assertIs<FileSystemException>(failure.exception)
            assertEquals(Ok(WalkEntry.Skipped(notebook / "loop", Path("loop"), WalkSkip.LinkLoop)), walked)
        } finally {
            Files.delete(loop)
        }
    }

    @Test
    fun `an Auto write by rename keeps the group of the file`() {
        val group = otherGroupOrNull() ?: return
        val file = (dir / "shared.toml").writeRaw("old")
        val nio = Paths.get(file.toString())
        Files.setAttribute(nio, "unix:gid", group)
        val before = file.inode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Auto).way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.inode())
        assertEquals(group, Files.getAttribute(nio, "unix:gid"))
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
    fun `a write through a dangling link whose target's folder may not be made names the file and the level refused`() {
        assumeFalse(Files.isWritable(Paths.get("/")), "this user may create folders in /")
        val missing = Path("/nonexistent-kern-${Uuid.random()}")
        val out = dir / "out"
        createDirectoriesRaw(out)
        val file = out / "summary.txt"
        Files.createSymbolicLink(Paths.get(file.toString()), Paths.get((missing / "zzz").toString()))
        val source = (dir / "source.txt").writeRaw("x")
        try {
            val refused = Err(FileError.WriteFailed(file, IoCause.Denied, null, at = FailedAt.Folder(missing)))

            assertEquals(refused, file.writeText("x"))
            assertEquals(refused, file.writeBytes(byteArrayOf(1), WriteMode.InPlace))
            assertEquals(refused, source.copyTo(file))
        } finally {
            Files.delete(Paths.get(file.toString()))
        }
    }

    @Test
    fun `a write whose folders cannot be made in a read-only folder names the file and the level refused`() {
        val out = dir / "out"
        createDirectoriesRaw(out)
        val nio = Paths.get(out.toString())
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("r-x------"))
        val file = out / "a" / "b.txt"
        val source = (dir / "source.txt").writeRaw("x")
        try {
            assumeFalse(Files.isWritable(nio), "this user writes folders without write permission")
            val refused = Err(FileError.WriteFailed(file, IoCause.Denied, null, at = FailedAt.Folder(out / "a")))

            assertEquals(refused, file.writeText("x"))
            assertEquals(refused, source.copyTo(file, WriteMode.InPlace))
            assertEquals(Err(FileError.WriteFailed(out / "a", IoCause.Denied, null)), (out / "a").createDirectories())
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `writeText refuses a file that may not be read for Denied and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        val nio = Paths.get(file.toString())
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("-w-------"))
        try {
            assumeFalse(Files.isReadable(nio), "this user reads files without read permission")

            assertEquals(IoCause.Denied, file.writeText("new").assertError<FileError.WriteFailed>().cause)
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-r--r--"))
        }
        assertEquals("old", file.readRaw())
    }

    /** Runs [write] on a file of mode 444 and checks it is refused for Denied, leaving the file as it was. */
    private fun assertReadOnlyRefused(write: (Path) -> Result<Copied, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        val nio = Paths.get(file.toString())
        val readOnly = PosixFilePermissions.fromString("r--r--r--")
        Files.setPosixFilePermissions(nio, readOnly)
        try {
            // The probe appends, which empties nothing.
            assumeFalse(
                runCatching { FileOutputStream(file.toString(), true).close() }.isSuccess,
                "this user writes files without write permission",
            )

            val failure = write(file).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
            assertEquals("old", file.readRaw())
            assertEquals(readOnly, Files.getPosixFilePermissions(nio))
            assertEquals(emptyList(), file.leftoverCopies<Leftover.Temp>().assertSuccess())
            assertEquals(emptyList(), file.leftoverCopies<Leftover.Backup>().assertSuccess())
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `the copy a write makes takes the file's own permissions`() {
        val file = (dir / "a.toml").writeRaw("secret")
        val copy = dir / "b.toml"
        val owned = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(Paths.get(file.toString()), owned)

        // Called directly because this is where a backup's permissions are decided, and because it is the
        // JVM's own implementation, which no test on a native target reaches.
        copyFile(from = file, to = copy).close()

        assertEquals(owned, Files.getPosixFilePermissions(Paths.get(copy.toString())))
    }

    @Test
    fun `the copy a write makes of a read-only file holds its content and ends read-only`() {
        val readOnly = PosixFilePermissions.fromString("r--r--r--")
        val file = (dir / "a.toml").writeRaw("old")
        val copy = dir / ".a.toml.$A_WRITE_ID.bak"
        Files.setPosixFilePermissions(Paths.get(file.toString()), readOnly)

        copyFile(from = file, to = copy).close()

        assertEquals("old", copy.readRaw())
        assertEquals(readOnly, Files.getPosixFilePermissions(Paths.get(copy.toString())))
    }

    @Test
    fun `a restore puts the backup back even when the backup's own mode would not let it be read`() {
        val file = (dir / "a.toml").writeRaw("old")
        val backupPath = dir / ".a.toml.$A_WRITE_ID.bak"
        val backup = copyFile(from = file, to = backupPath)
        file.writeRaw("half")
        Files.setPosixFilePermissions(Paths.get(backupPath.toString()), PosixFilePermissions.fromString("-w-------"))

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

    /** Runs [write] onto a missing file and checks the file it made is `0600` and holds `new`. */
    private fun assertCreatedOwnerOnly(write: (Path) -> Result<Copied, FileError>) {
        val file = dir / "new.toml"
        val plain = dir / "plain.toml"
        assertEquals(Ok(WriteWay.Renamed), plain.writeText("plain").way())
        // The JVM cannot set the umask, so only one that leaves a new file wider than 0600 shows anything.
        assumeFalse(permissionsOf(plain) == ownerReadWrite, "the umask already leaves a new file 0600")

        write(file).assertSuccess()

        assertEquals("new", file.readRaw())
        assertEquals(ownerReadWrite, permissionsOf(file))
    }

    /** Runs [write] onto an existing `0644` file and checks it holds `new` and is `0600`. */
    private fun assertExistingNarrowed(write: (Path) -> Result<Copied, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("rw-r--r--"))

        write(file).assertSuccess()

        assertEquals("new", file.readRaw())
        assertEquals(ownerReadWrite, permissionsOf(file))
    }

    @Test
    fun `a rename write with ownerOnly makes its temp 0600 before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("rw-r--r--"))
        var tempPermissions: String? = null

        val written = file.write(WriteMode.Replace, ownerOnly = true) { sink ->
            tempPermissions = permissionsOf(
                file
                    .leftoverCopies<Leftover.Temp>()
                    .assertSuccess()
                    .single(),
            )
            sink.writeString("new")
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals(ownerReadWrite, tempPermissions)
    }

    @Test
    fun `an in-place write with ownerOnly narrows the file and its backup to 0600 before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("rw-r--r--"))
        var filePermissions: String? = null
        var backupPermissions: String? = null

        val written = file.write(WriteMode.InPlace, ownerOnly = true) { sink ->
            filePermissions = permissionsOf(file)
            backupPermissions = permissionsOf(
                file
                    .leftoverCopies<Leftover.Backup>()
                    .assertSuccess()
                    .single(),
            )
            sink.writeString("new")
        }

        assertEquals(Ok(WriteWay.InPlace), written.way())
        assertEquals(ownerReadWrite, filePermissions)
        assertEquals(ownerReadWrite, backupPermissions)
    }

    private val ownerReadWrite = "rw-------"

    private fun permissionsOf(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(path.toString())))

    @Test
    fun `a new file written without ownerOnly follows the umask`() {
        // The JVM cannot set the umask, so a file the JDK makes here shows what it is.
        val made = Files.createFile(Paths.get((dir / "made.toml").toString()))
        val file = dir / "new.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())

        assertEquals(Files.getPosixFilePermissions(made), Files.getPosixFilePermissions(Paths.get(file.toString())))
    }

    @Test
    fun `copyTo with keepAttributes gives a new target the source's 600 mode`() = assertModeKept("rw-------")

    @Test
    fun `copyTo with keepAttributes gives a new target the source's 755 mode`() = assertModeKept("rwxr-xr-x")

    /** Copies a source of [mode] to a new target keeping attributes, and checks the target has [mode]. */
    private fun assertModeKept(mode: String) {
        val source = (dir / "a.sh").writeRaw("content")
        Files.setPosixFilePermissions(Paths.get(source.toString()), PosixFilePermissions.fromString(mode))
        val target = dir / "b.sh"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target, keepAttributes = true).way())

        assertEquals("content", target.readRaw())
        assertEquals(mode, permissionsOf(target))
    }

    @Test
    fun `an InPlace copyTo with keepAttributes gives the target the source's mode and keeps the target itself`() {
        val source = (dir / "a.sh").writeRaw("new")
        Files.setPosixFilePermissions(Paths.get(source.toString()), PosixFilePermissions.fromString("rwxr-x---"))
        val target = (dir / "b.sh").writeRaw("old")
        val before = target.inode()

        assertEquals(Ok(WriteWay.InPlace), source.copyTo(target, WriteMode.InPlace, keepAttributes = true).way())

        assertEquals("new", target.readRaw())
        assertEquals(before, target.inode())
        assertEquals("rwxr-x---", permissionsOf(target))
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
        val source = (dir / "a.txt").writeRaw("new").stampedLongAgo()
        val target = (dir / "b.txt").writeRaw("old")

        assertEquals(Ok(written), source.copyTo(target, mode, keepAttributes = true).way())

        assertEquals("new", target.readRaw())
        assertEquals(source.modifiedAt(), target.modifiedAt())
    }

    @Test
    fun `copyTo with keepAttributes and ownerOnly leaves the target 0600 whatever the source's mode`() {
        val source = (dir / "a.sh").writeRaw("content")
        Files.setPosixFilePermissions(Paths.get(source.toString()), PosixFilePermissions.fromString("rwxr-xr-x"))
        val target = dir / "b.sh"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target, ownerOnly = true, keepAttributes = true).way())

        assertEquals(ownerReadWrite, permissionsOf(target))
    }

    @Test
    fun `copyTo without keepAttributes gives a new target the mode and time any new file gets`() {
        val source = (dir / "a.sh").writeRaw("content").stampedLongAgo()
        Files.setPosixFilePermissions(Paths.get(source.toString()), PosixFilePermissions.fromString("rwx------"))
        val made = Files.createFile(Paths.get((dir / "made.txt").toString()))
        val target = dir / "b.sh"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())

        assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(made)), permissionsOf(target))
        assertNotEquals(source.modifiedAt(), target.modifiedAt())
    }

    @Test
    fun `copyTo onto itself with keepAttributes changes neither mode nor time`() {
        val file = (dir / "a.txt").writeRaw("content")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("rw-r-----"))
        val modified = file.modifiedAt()

        assertIs<Copied.Unchanged>(file.copyTo(file, keepAttributes = true).assertSuccess())

        assertEquals("rw-r-----", permissionsOf(file))
        assertEquals(modified, file.modifiedAt())
    }

    @Test
    fun `a rename write keeping attributes gives its temp the kept mode before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        var tempPermissions: String? = null

        val written = file.write(WriteMode.Replace, kept = KeptAttributes(permissions = 0b111_101_000, longAgo)) {
            tempPermissions = permissionsOf(
                file
                    .leftoverCopies<Leftover.Temp>()
                    .assertSuccess()
                    .single(),
            )
            it.writeString("new")
        }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals("rwxr-x---", tempPermissions)
    }

    @Test
    fun `an in-place write keeping attributes gives the file the kept mode before any content goes in`() {
        val file = (dir / "a.toml").writeRaw("old")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("rw-r--r--"))
        var filePermissions: String? = null
        var backupPermissions: String? = null

        val written = file.write(WriteMode.InPlace, kept = KeptAttributes(permissions = 0b111_101_000, longAgo)) {
            filePermissions = permissionsOf(file)
            backupPermissions = permissionsOf(
                file
                    .leftoverCopies<Leftover.Backup>()
                    .assertSuccess()
                    .single(),
            )
            it.writeString("new")
        }

        assertEquals(Ok(WriteWay.InPlace), written.way())
        assertEquals("rwxr-x---", filePermissions)
        assertEquals("rw-r--r--", backupPermissions)
    }

    @Test
    fun `an in-place write makes its backup under a partial name and leaves nothing when the copy fails`() {
        val file = (dir / "a.toml").writeRaw("old")
        var copiedTo: Path? = null

        val written = file.write(WriteMode.InPlace, backUp = { from, to, ownerOnly ->
            copiedTo = to
            copyFile(from, to, ownerOnly).close()
            throw IOException("device failed")
        }) { it.writeString("new") }

        written.assertError<FileError.WriteFailed>()
        assertTrue(assertNotNull(copiedTo).name.endsWith(".bak.part"))
        assertEquals("old", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an in-place write whose backup copy fails and cannot clean up leaves only a partial backup`() {
        val file = (dir / "a.toml").writeRaw("old")
        val folder = File(dir.toString())

        try {
            val written = file.write(WriteMode.InPlace, backUp = { from, to, ownerOnly ->
                copyFile(from, to, ownerOnly).close()
                folder.setWritable(false)
                throw IOException("device failed")
            }) { it.writeString("new") }
            assumeFalse(File(folder, "probe").mkdir(), "this user writes into folders without write permission")

            written.assertError<FileError.WriteFailed>()
            assertEquals("old", file.readRaw())
            assertIs<Leftover.PartialBackup>(file.leftovers().assertSuccess().single())
        } finally {
            folder.setWritable(true)
        }
    }

    private val longAgo = Instant.fromEpochSeconds(981_173_106, 123_456_789)

    /** Sets this file's modification time to [longAgo], to the precision the file system keeps. */
    private fun Path.stampedLongAgo(): Path = also {
        val time = java.time.Instant.ofEpochSecond(longAgo.epochSeconds, longAgo.nanosecondsOfSecond.toLong())
        Files.setLastModifiedTime(Paths.get(toString()), FileTime.from(time))
    }
}
