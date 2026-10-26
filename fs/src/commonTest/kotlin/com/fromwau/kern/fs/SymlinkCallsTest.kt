package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class SymlinkCallsTest {
    private val dir = newTempDir()
    private val link = dir / "link"

    @AfterTest
    fun cleanUp() {
        val _ = link.delete()
        dir.deleteTree()
    }

    @Test
    fun `a link made to a relative target reads back as written and leads to the file beside it`() {
        (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), link.createSymlink(Path("a.txt")))

        assertEquals(Ok(Path("a.txt")), link.readSymlink())
        assertEquals(Ok("a"), link.readText())
    }

    @Test
    fun `a link made to an absolute target reads back as written`() {
        val target = (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), link.createSymlink(target))

        assertEquals(Ok(target), link.readSymlink())
    }

    @Test
    fun `a link may be made to a target that does not exist`() {
        val missing = dir / "missing"

        assertEquals(Ok(Unit), link.createSymlink(missing))

        assertEquals(Ok(missing), link.readSymlink())
        assertEquals(Ok(true), link.isSymlink())
    }

    @Test
    fun `a link whose target is missing moves as itself`() {
        val staged = dir / "staged"
        staged.createSymlink(Path("missing")).assertSuccess()

        assertEquals(Ok(Unit), staged.moveTo(link))

        assertEquals(Ok(Path("missing")), link.readSymlink())
        assertEquals(Err(FileError.NotFound(staged)), staged.isSymlink())
    }

    @Test
    fun `a link moved onto a link replaces it also when its target does not exist yet`() {
        createDirectoriesRaw(dir / "release-1")
        link.createSymlink(Path("release-1")).assertSuccess()
        val staged = dir / "staged"
        staged.createSymlink(Path("release-2")).assertSuccess()

        assertEquals(Ok(Unit), staged.moveTo(link))

        assertEquals(Ok(Path("release-2")), link.readSymlink())
        assertEquals(setOf(link, dir / "release-1"), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `a link is never made over something already there and that is AlreadyExists`() {
        val file = (dir / "link").writeRaw("kept")

        assertEquals(Err(FileError.AlreadyExists(file, FileType.Regular)), link.createSymlink(dir / "other"))

        assertEquals(Ok(false), file.isSymlink())
        assertEquals("kept", file.readRaw())
    }

    @Test
    fun `a link over a folder or another link is AlreadyExists naming what is there`() {
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        assertEquals(Ok(Unit), link.createSymlink(dir / "missing"))

        assertEquals(Err(FileError.AlreadyExists(folder, FileType.Directory)), folder.createSymlink(dir / "other"))
        assertEquals(Err(FileError.AlreadyExists(link, FileType.Symlink)), link.createSymlink(dir / "other"))
        assertEquals(Ok(dir / "missing"), link.readSymlink())
    }

    @Test
    fun `a link in a missing folder is WriteFailed as NotFound and no folder is made`() {
        val inMissing = dir / "missing" / "link"

        val failure = inMissing.createSymlink(Path("a.txt")).assertError<FileError.WriteFailed>()

        assertEquals(IoCause.NotFound, failure.cause)
        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").fileType())
    }

    @Test
    fun `reading a regular file as a link is NotASymlink naming what is there`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Err(FileError.NotASymlink(file, FileType.Regular)), file.readSymlink())
    }

    @Test
    fun `reading a folder as a link is NotASymlink naming it a directory`() {
        assertEquals(Err(FileError.NotASymlink(dir, FileType.Directory)), dir.readSymlink())
    }

    @Test
    fun `reading a missing path as a link is NotFound`() {
        assertEquals(Err(FileError.NotFound(link)), link.readSymlink())
    }

    @Test
    fun `canonicalPath resolves a missing chain through a symlinked folder`() {
        val real = dir / "real"
        createDirectoriesRaw(real)
        val _ = link.createSymlink(real)

        val expected = Path(real.realPath().assertSuccess(), "missing", "a.txt")
        assertEquals(Ok(expected), Path(link, "missing", "a.txt").canonicalPath())
    }

    @Test
    fun `a link removed just before its content is read is NotFound`() {
        val _ = link.createSymlink(Path("a.txt"))

        val read = link.readSymlinkWith {
            val _ = deleteLink(it)
            readLink(it)
        }

        assertEquals(Err(FileError.NotFound(link)), read)
    }

    @Test
    fun `a link whose content cannot be read is Inaccessible for the reason the read gave`() {
        val _ = link.createSymlink(Path("a.txt"))
        val denied = OsCallFailure(IoCause.Denied, link, "denied")

        val failure = link.readSymlinkWith { throw denied }.assertError<FileError.Inaccessible>()

        assertEquals(IoCause.Denied, failure.cause)
        assertEquals(denied, failure.exception)
    }

    @Test
    fun `isSymlink of a path nothing is at is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.isSymlink())
    }
}
