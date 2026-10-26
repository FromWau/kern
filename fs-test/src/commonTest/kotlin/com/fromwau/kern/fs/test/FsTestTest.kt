package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.LongName
import com.fromwau.kern.fs.NameProblem
import com.fromwau.kern.fs.Permissions
import com.fromwau.kern.fs.WriteMode
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.fileType
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.list
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.readSymlink
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class FsTestTest {
    private val dir = scratchDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    @Test
    fun `scratchDir is a new empty folder of its own each time`() {
        val other = scratchDir()

        try {
            assertNotEquals(dir, other)
            assertTrue(other.isAbsolute)
            assertEquals(Ok(emptyList()), other.list())
        } finally {
            other.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `scratchDir is 755`() {
        dir.permissions().assertSuccess()?.let { assertEquals(Permissions(0b111_101_101), it) }
    }

    @Test
    fun `the temp root is TMPDIR when it is an absolute path and the platform's otherwise`() {
        assertEquals(Path("/scratch"), tempRoot(mapOf("TMPDIR" to "/scratch")::get))
        assertEquals(platformTempRoot(), tempRoot(mapOf("TMPDIR" to "relative")::get))
        assertEquals(platformTempRoot(), tempRoot(emptyMap<String, String>()::get))
        assertTrue(platformTempRoot().isAbsolute)
    }

    @Test
    fun `symlinkTo makes a link holding its target as given and never replaces anything`() {
        val link = dir / "link"

        link.symlinkTo(Path("missing")).assertSuccess()

        assertEquals(Ok(Path("missing")), link.readSymlink())
        assertEquals(Err(FileError.AlreadyExists(link, FileType.Symlink)), link.symlinkTo(Path("other")))
    }

    @Test
    fun `symlinkTo makes the missing folders on the way`() {
        val link = dir / "a" / "b" / "link"

        link.symlinkTo(Path("target")).assertSuccess()

        assertEquals(Ok(Path("target")), link.readSymlink())
    }

    @Test
    fun `symlinkTo below a file is NotFound naming the folder it cannot make`() {
        val file = dir / "file"
        file.writeText("x").assertSuccess()

        assertEquals(Err(FileError.NotFound(file / "sub")), (file / "sub" / "link").symlinkTo(Path("target")))
    }

    @Test
    fun `hardLinkTo gives the file a second name that sees every write`() {
        val file = dir / "a.txt"
        file.writeText("one").assertSuccess()
        val second = dir / "b.txt"

        second.hardLinkTo(file).assertSuccess()
        file.writeText("two", WriteMode.InPlace).assertSuccess()

        assertEquals(Ok("two"), second.readText())
        assertEquals(Err(FileError.AlreadyExists(second, FileType.Regular)), second.hardLinkTo(file))
    }

    @Test
    fun `hardLinkTo makes the missing folders on the way`() {
        val file = dir / "a.txt"
        file.writeText("one").assertSuccess()
        val link = dir / "x" / "y" / "a.txt"

        link.hardLinkTo(file).assertSuccess()

        assertEquals(Ok("one"), link.readText())
    }

    @Test
    fun `hardLinkTo below a file is NotFound naming the folder it cannot make`() {
        val file = dir / "file"
        file.writeText("x").assertSuccess()

        assertEquals(Err(FileError.NotFound(file / "sub")), (file / "sub" / "link").hardLinkTo(file))
    }

    @Test
    fun `hardLinkTo a missing file is WriteFailed with the cause NotFound`() {
        val link = dir / "b.txt"

        assertEquals(IoCause.NotFound, link.hardLinkTo(dir / "missing").assertError<FileError.WriteFailed>().cause)
    }

    @Test
    fun `chmod sets the permission bits where the platform has them`() {
        val file = dir / "a.txt"
        file.writeText("a").assertSuccess()

        file.chmod(0b110_000_000).assertSuccess()

        file.permissions().assertSuccess()?.let { assertEquals(Permissions(0b110_000_000), it) }
        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").chmod(0b110_000_000))
        val underMissing = dir / "missing" / "a.txt"
        assertEquals(Err(FileError.NotFound(underMissing)), underMissing.chmod(0b110_000_000))
    }

    @Test
    fun `chmod of a path holding a NUL is InvalidName and leaves the path before the NUL alone`() {
        val file = dir / "a.txt"
        file.writeText("a").assertSuccess()
        file.chmod(0b110_000_000).assertSuccess()
        val withNul = Path("$file\u0000b")

        assertEquals(Err(FileError.InvalidName(withNul, NameProblem.Nul)), withNul.chmod(0b111_111_111))

        file.permissions().assertSuccess()?.let { assertEquals(Permissions(0b110_000_000), it) }
    }

    @Test
    fun `setModifiedAt sets the time info reads back`() {
        val file = dir / "a.txt"
        file.writeText("a").assertSuccess()
        val at = Instant.parse("2001-02-03T04:05:06.123456Z")

        file.setModifiedAt(at).assertSuccess()

        assertEquals(at, file.info().assertSuccess().modifiedAt)
    }

    @Test
    fun `setModifiedAt of nothing is NotFound and of a path holding a NUL InvalidName`() {
        val at = Instant.parse("2001-02-03T04:05:06Z")
        val withNul = Path("${dir / "a.txt"}\u0000b")

        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").setModifiedAt(at))
        assertEquals(Err(FileError.InvalidName(withNul, NameProblem.Nul)), withNul.setModifiedAt(at))
    }

    @Test
    fun `mkfifo of a name longer than 255 bytes is NameTooLong`() {
        val fifo = dir / "a".repeat(256)

        assertEquals(Err(FileError.NameTooLong(fifo, LongName.Path, 256)), fifo.mkfifo())
    }

    @Test
    fun `hardLinkTo at the empty path is NotFound`() {
        val file = dir / "a.txt"
        file.writeText("a").assertSuccess()

        assertEquals(Err(FileError.NotFound(Path(""))), Path("").hardLinkTo(file))
    }

    @Test
    fun `hardLinkTo a target holding a NUL is InvalidName`() {
        val target = Path("${dir / "a.txt"}\u0000b")

        assertEquals(Err(FileError.InvalidName(target, NameProblem.Nul)), (dir / "b.txt").hardLinkTo(target))
    }
}
