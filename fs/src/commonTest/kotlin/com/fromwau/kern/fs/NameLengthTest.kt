package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * An in-place backup `.<name>.<id>.bak.part` is 47 bytes longer than the name, so with names of at most 255 bytes an
 * in-place write takes one of up to 208, while a rename takes every name up to 255. A name in the path itself longer
 * than 255 bytes is refused for every call.
 */
class NameLengthTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun named(bytes: Int): Path = dir / "a".repeat(bytes)

    private fun entries(): Set<Path> = SystemFileSystem.list(dir).toSet()

    @Test
    fun `MAX_STAGED_NAME_BYTES is 212`() {
        assertEquals(212, MAX_STAGED_NAME_BYTES)
    }

    @Test
    fun `a link switch and a stage take a name of MAX_STAGED_NAME_BYTES and refuse one a byte longer`() {
        val writer = dir.writer().assertSuccess()
        val fits = "a".repeat(MAX_STAGED_NAME_BYTES)
        val over = "b".repeat(MAX_STAGED_NAME_BYTES + 1)
        // `.<name>.<id>.link` and the claim `.<name>.<id>.lock` are 43 bytes longer than the name.
        val overBytes = MAX_STAGED_NAME_BYTES + 1 + 43

        assertEquals(Ok(Unit), (dir / fits).switchSymlinkTo(Path("x")))
        assertEquals(Ok(Unit), writer.switchSymlink("w/$fits", Path("x")))
        assertEquals(Ok(Unit), writer.createDirectoryAtomically("s/$fits") { })
        assertEquals(Ok(Unit), (dir / "p" / fits).createDirectoryAtomically { })

        val linkTooLong = Err(FileError.NameTooLong(dir / over, LongName.StagedLink, overBytes))
        assertEquals(linkTooLong, (dir / over).switchSymlinkTo(Path("x")))
        val writerLinkTooLong = Err(FileError.NameTooLong(writer.root / over, LongName.StagedLink, overBytes))
        assertEquals(writerLinkTooLong, writer.switchSymlink(over, Path("x")))
        val writerStageTooLong = Err(FileError.NameTooLong(writer.root / over, LongName.Stage, overBytes))
        assertEquals(writerStageTooLong, writer.createDirectoryAtomically(over) { })
        val stageTooLong = Err(FileError.NameTooLong(dir / "p" / over, LongName.Stage, overBytes))
        assertEquals(stageTooLong, (dir / "p" / over).createDirectoryAtomically { })
    }

    @Test
    fun `a stage and a link switch refuse exactly the names nameProblem with staged calls TooLongToStage`() {
        val writer = dir.writer().assertSuccess()
        for (name in listOf("é".repeat(106), "é".repeat(106) + "a", "€".repeat(71), "a".repeat(255))) {
            val tooLong = name.nameProblem(staged = true) == NameProblem.TooLongToStage
            val built = writer.createDirectoryAtomically(name) { }
            val switched = writer.switchSymlink("links/$name", Path("x"))

            assertEquals(tooLong, built.errorOrNull() is FileError.NameTooLong, "${name.length} characters")
            assertEquals(tooLong, switched.errorOrNull() is FileError.NameTooLong, "${name.length} characters")
            if (!tooLong) assertEquals(Ok(Unit), built, "${name.length} characters")
        }
    }

    @Test
    fun `a rename write takes a name of 213 bytes`() {
        assertEquals(Ok(WriteWay.Renamed), named(213).writeText("new", mode = WriteMode.Atomic).way())
    }

    @Test
    fun `a rename write of a name of 214 or 230 bytes creates it and the folders it needs`() {
        for (bytes in listOf(214, 230)) {
            val file = dir / "sub" / "a".repeat(bytes)

            assertEquals(Ok(WriteWay.Renamed), file.writeText("new", mode = WriteMode.Replace).way())
            assertEquals(listOf(file), SystemFileSystem.list(dir / "sub"))
            SystemFileSystem.delete(file)
        }
    }

    @Test
    fun `an in-place write takes a name of 208 bytes`() {
        val file = named(208).writeRaw("old")

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", mode = WriteMode.InPlace).way())
        assertEquals("new", file.readRaw())
    }

    @Test
    fun `an in-place write of a name of 209 213 or 230 bytes is NameTooLong and changes nothing`() {
        for (bytes in listOf(209, 213, 230)) {
            val file = named(bytes).writeRaw("old")

            val written = file.writeText("new", mode = WriteMode.InPlace)

            assertEquals(Err(FileError.NameTooLong(file, LongName.PartialBackup, bytes = bytes + 47)), written)
            assertEquals("old", file.readRaw())
            assertEquals(setOf(file), entries())
            SystemFileSystem.delete(file)
        }
    }

    @Test
    fun `an in-place write creating a file of a name too long for a backup needs none`() {
        val file = named(230)

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", mode = WriteMode.InPlace).way())
    }

    @Test
    fun `a copy onto a name of 214 bytes is written by rename`() {
        val source = (dir / "source.txt").writeRaw("content")
        val target = named(214)

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())
        assertEquals(setOf(source, target), entries())
    }

    @Test
    fun `a read of a name of 256 bytes is NameTooLong naming the path`() {
        val file = named(256)

        assertEquals(Err(FileError.NameTooLong(file, LongName.Path, bytes = 256)), file.readText())
        assertEquals(Err(FileError.NameTooLong(file, LongName.Path, bytes = 256)), file.fileSize())
    }

    @Test
    fun `a write of a name of 256 bytes is NameTooLong naming the path in every mode and creates nothing`() {
        val file = dir / "sub" / "a".repeat(256)

        for (mode in listOf(WriteMode.Auto, WriteMode.Atomic, WriteMode.Replace, WriteMode.InPlace)) {
            assertEquals(Err(FileError.NameTooLong(file, LongName.Path, bytes = 256)), file.writeText("new", mode))
        }
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `a copy from or onto a name of 256 bytes is NameTooLong naming that path`() {
        val source = (dir / "source.txt").writeRaw("content")
        val long = named(256)

        assertEquals(Err(FileError.NameTooLong(long, LongName.Path, bytes = 256)), long.copyTo(source))
        assertEquals(Err(FileError.NameTooLong(long, LongName.Path, bytes = 256)), source.copyTo(long))
        assertEquals("content", source.readRaw())
        assertEquals(setOf(source), entries())
    }

    @Test
    fun `a walk from a folder below a name of 256 bytes is NameTooLong naming the root`() {
        val root = named(256) / "sub"

        assertEquals(Err(FileError.NameTooLong(root, LongName.Path, bytes = 256)), root.walkTopDown())
    }

    @Test
    fun `a name of 255 bytes is no NameTooLong of the path`() {
        val file = named(255)

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", mode = WriteMode.InPlace).way())
        assertEquals(Ok("new"), file.readText())
        assertEquals(Ok(FileType.Regular), file.fileType())
        assertEquals(Ok(WriteWay.Renamed), file.writeText("x", mode = WriteMode.Replace).way())
    }

    @Test
    fun `a name counts in UTF-8 bytes also for the path itself`() {
        // 128 two-byte letters are 256 bytes, one more than a name may take.
        val file = dir / "ä".repeat(128)

        assertEquals(Err(FileError.NameTooLong(file, LongName.Path, bytes = 256)), file.readText())
    }

    @Test
    fun `two refusals of the same write are equal`() {
        val file = named(230).writeRaw("old")

        assertEquals(file.writeText("new", WriteMode.InPlace), file.writeText("new", WriteMode.InPlace))
    }

    @Test
    fun `a name counts in UTF-8 bytes`() {
        // 105 two-byte letters are 210 bytes, two more than an in-place backup leaves room for.
        val file = (dir / "ä".repeat(105)).writeRaw("old")

        val written = file.writeText("new", mode = WriteMode.InPlace)

        assertEquals(Err(FileError.NameTooLong(file, LongName.PartialBackup, bytes = 210 + 47)), written)
    }

    @Test
    fun `an Auto write renames a lone own file of 209 and of 213 bytes`() {
        for (bytes in listOf(209, 213)) {
            val file = named(bytes).writeRaw("old")

            assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())
            assertEquals("new", file.readRaw())
            assertEquals(setOf(file), entries())
            SystemFileSystem.delete(file)
        }
    }

    @Test
    fun `an Auto write falling back to in place refuses a name too long for its backup and leaves nothing behind`() {
        for (bytes in listOf(209, 213)) {
            val file = named(bytes).writeRaw("old")

            val written = file.write(WriteMode.Auto, keepGroup = { _, _ -> false }) { error("wrote content") }

            assertEquals(Err(FileError.NameTooLong(file, LongName.PartialBackup, bytes = bytes + 47)), written)
            assertEquals("old", file.readRaw())
            assertEquals(setOf(file), entries())
            SystemFileSystem.delete(file)
        }
    }
}
