package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

class FileReadsJvmTest {
    private val dir = newTempDir()
    private val locked = dir / "locked"
    private val links = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        restore(locked)
        links.forEach { Files.deleteIfExists(Paths.get(it)) }
        dir.deleteTree()
    }

    @Test
    fun `a file under a directory that may not be entered is Inaccessible for Denied rather than NotFound`() {
        createDirectoriesRaw(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        shutOut(locked)

        assumeFalse(canLookUp(file), "this user looks up paths in folders it may not search")

        val error = file.readText().assertError<FileError.Inaccessible>()
        assertEquals(file, error.path)
        assertEquals(IoCause.Denied, error.cause)
        assertIs<AccessDeniedException>(error.exception)
    }

    @Test
    fun `permissions of a file under a directory that may not be entered is Inaccessible for Denied`() {
        createDirectoriesRaw(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        shutOut(locked)

        assumeFalse(canLookUp(file), "this user looks up paths in folders it may not search")

        val error = file.permissions().assertError<FileError.Inaccessible>()
        assertEquals(file, error.path)
        assertEquals(IoCause.Denied, error.cause)
    }

    @Test
    fun `deleteRecursively of a folder with entries that may not be listed is Inaccessible for Denied`() {
        createDirectoriesRaw(locked)
        (locked / "app.toml").writeRaw("port = 1")
        shutOut(locked)

        assumeFalse(canList(locked), "this user lists folders it may not read")

        val error = locked.deleteRecursively().assertError<FileError.Inaccessible>()
        assertEquals(locked, error.path)
        assertEquals(IoCause.Denied, error.cause)
    }

    @Test
    fun `a directory under one that may not be entered is Inaccessible rather than NotFound`() {
        val inner = locked / "inner"
        createDirectoriesRaw(inner)
        shutOut(locked)

        assumeFalse(canLookUp(inner), "this user looks up paths in folders it may not search")

        inner.list().assertError<FileError.Inaccessible>()
    }

    @Test
    fun `a directory that may not be read is Inaccessible rather than empty`() {
        createDirectoriesRaw(locked)
        (locked / "app.toml").writeRaw("port = 1")
        File(locked.toString()).setReadable(false)

        assumeFalse(canList(locked), "this user lists folders it may not read")

        val error = locked.list().assertError<FileError.Inaccessible>()
        assertEquals(locked, error.path)
    }

    @Test
    fun `a walk reports a directory it may not read instead of passing over it`() {
        createDirectoriesRaw(locked)
        (locked / "hidden.toml").writeRaw("port = 1")
        val top = (dir / "top.toml").writeRaw("x")
        // Readable off, executable left on, so the walk still sees a directory worth descending into.
        File(locked.toString()).setReadable(false)

        assumeFalse(canList(locked), "this user lists folders it may not read")

        val walked = dir.walkTopDown().assertSuccess().walkedFiles()
        assertEquals(listOf(Ok(top)), walked.filterIsInstance<Result.Success<Path>>())
        assertTrue(walked.any { (it.errorOrNull()?.error as? FileError.Inaccessible)?.path == locked })
    }

    @Test
    fun `an empty directory lists as empty`() {
        val empty = dir / "empty"
        createDirectoriesRaw(empty)

        assertEquals(Ok(emptyList()), empty.list())
    }

    @Test
    fun `a symlink that loops is Inaccessible`() {
        val first = dir / "first"
        val second = dir / "second"
        links += listOf(first.toString(), second.toString())
        Files.createSymbolicLink(Paths.get(first.toString()), Paths.get(second.toString()))
        Files.createSymbolicLink(Paths.get(second.toString()), Paths.get(first.toString()))

        first.readText().assertError<FileError.Inaccessible>()
    }

    @Test
    fun `modifiedAt reads the time to the nanosecond the file system keeps`() {
        val file = (dir / "stamped.txt").writeRaw("x")
        val stamp = java.time.Instant.parse("2024-02-29T12:34:56.123456789Z")
        Files.setLastModifiedTime(Paths.get(file.toString()), FileTime.from(stamp))
        val kept = Files.getLastModifiedTime(Paths.get(file.toString())).toInstant()

        val read = file.modifiedAt().assertSuccess()

        assertEquals(Instant.fromEpochSeconds(kept.epochSecond, kept.nano), read)
        // Only a file system that kept every digit can show none of them is dropped on the way.
        if (kept == stamp) assertEquals(Instant.fromEpochSeconds(1709210096, 123456789), read)
    }

    @Test
    fun `fileType tells a FIFO from a character device and a socket where the file system has unix attributes`() {
        assumeTrue(hasUnixView, "no unix attributes to read a file's kind from")
        val fifo = dir / "pipe"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")
        val socket = dir / "sock"
        ServerSocketChannel
            .open(StandardProtocolFamily.UNIX)
            .use { it.bind(UnixDomainSocketAddress.of(socket.toString())) }

        assertEquals(Ok(FileType.Fifo), fifo.fileType())
        assertEquals(Ok(FileType.CharacterDevice), Path("/dev/null").fileType())
        assertEquals(Ok(FileType.Socket), socket.fileType())
    }

    @Test
    fun `info and fileType name a character device and a block device where the file system has unix attributes`() {
        assumeTrue(hasUnixView, "no unix attributes to read a file's kind from")
        val block = firstBlockDevice().also { assumeTrue(it != null, "no block device in /dev to look up") } ?: return

        assertEquals(FileType.CharacterDevice, Path("/dev/null").info().assertSuccess().type)
        assertEquals(FileType.BlockDevice, block.info().assertSuccess().type)
        assertEquals(Ok(FileType.BlockDevice), block.fileType())
    }

    @Test
    fun `a writer read of a folder is NotRegularFile`() {
        createDirectoriesRaw(dir / "folder")
        val writer = dir.writer().assertSuccess()

        val expected = Err(FileError.NotRegularFile(writer.root / "folder", FileType.Directory))
        assertEquals(expected, writer.readText("folder"))
    }

    @Test
    fun `a writer read of a FIFO is NotRegularFile without waiting for a writer`() {
        val fifo = dir / "pipe"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")
        val writer = dir.writer().assertSuccess()
        var read: Result<String, FileError>? = null
        val reader = thread(isDaemon = true) { read = writer.readText("pipe") }

        reader.join(FIFO_WAIT_MILLIS)
        if (reader.isAlive) {
            // Opening the other end frees the read; a daemon thread and bounded joins keep the test from hanging.
            val opener = thread(isDaemon = true) { Files.newOutputStream(Paths.get(fifo.toString())).close() }
            opener.join(FIFO_WAIT_MILLIS)
            reader.join(FIFO_WAIT_MILLIS)
            fail("the read waited on the FIFO and answered $read")
        }
        assertEquals(Err(FileError.NotRegularFile(writer.root / "pipe", FileType.Fifo)), read)
    }

    @Test
    fun `reading the info of a missing file throws what java nio raised`() {
        assertFailsWith<NoSuchFileException> { fileInfoAt(dir / "missing") }
    }

    @Test
    fun `resolving a missing path throws what java nio raised`() {
        assertFailsWith<NoSuchFileException> { realPath(dir / "missing") }
    }

    @Test
    fun `readText of a file this user may not read is Inaccessible for Denied`() =
        assertUnreadableRefused { it.readText() }

    @Test
    fun `readBytes of a file this user may not read is Inaccessible for Denied`() =
        assertUnreadableRefused { it.readBytes() }

    @Test
    fun `copyTo from a file this user may not read fails with what java nio raised`() =
        assertUnreadableRefused { it.copyTo(dir / "copy.toml") }

    /** Runs [read] on a file with no permissions at all and checks it is Inaccessible for Denied, naming it. */
    private fun assertUnreadableRefused(read: (Path) -> Result<Any?, FileError>) {
        val file = (dir / "a.toml").writeRaw("old")
        val nio = Paths.get(file.toString())
        Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("---------"))
        try {
            assumeFalse(Files.isReadable(nio), "this user reads files without read permission")

            val failure = read(file).assertError<FileError.Inaccessible>()

            assertEquals(file, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            assertIs<AccessDeniedException>(failure.exception)
        } finally {
            Files.setPosixFilePermissions(nio, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    @Test
    fun `a file moved over the path after the open fails Inaccessible without asking accept`() {
        val file = (dir / "a.txt").writeRaw("abc")
        val swapped = (dir / "b.txt").writeRaw("longer content")
        var asked = false

        val read = file.readWhole(
            maxBytes = null,
            accept = {
                asked = true
                true
            },
            onOpened = { SystemFileSystem.atomicMove(swapped, file) },
        )

        assertEquals(file, read.assertError<FileError.Inaccessible>().path)
        assertFalse(asked)
    }
}

/** How long a read may take before it counts as waiting on a FIFO. */
private const val FIFO_WAIT_MILLIS = 5_000L
