package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LinkAtTheNameTest {
    private val dir = newTempDir()
    private val outside = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
        outside.deleteTree()
    }

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    @Test
    fun `a path write writes through a link into the file it leads to`() {
        val theirs = (outside / "theirs.txt").writeRaw("theirs")
        val link = (dir / "a.txt").linkTo(theirs)

        link.writeText("mine").assertSuccess()

        assertEquals(Ok(true), link.isSymlink())
        assertEquals("mine", theirs.readRaw())
    }

    @Test
    fun `a path copy onto a link to the source itself is Unchanged`() {
        val source = (dir / "source.txt").writeRaw("content")
        val link = (dir / "copy.txt").linkTo(source)

        assertIs<Copied.Unchanged>(source.copyTo(link).assertSuccess())

        assertEquals(Ok(true), link.isSymlink())
    }

    @Test
    fun `a writer write refuses a link to a file beside it and leaves both alone`() {
        val mine = (dir / "real.txt").writeRaw("old")
        val link = (dir / "a.txt").linkTo(Path("real.txt"))
        val writer = dir.writer().assertSuccess()

        val refused = Err(FileError.NotRegularFile(writer.root / "a.txt", FileType.Symlink))
        assertEquals(refused, writer.writeText("a.txt", "mine"))
        assertEquals(refused, writer.writeText("a.txt", "mine", inPlace = true))
        assertEquals(refused, writer.copyFrom(mine, "a.txt"))

        assertEquals(Ok(Path("real.txt")), link.readSymlink())
        assertEquals("old", mine.readRaw())
    }

    @Test
    fun `an in-place writer write refuses a link to a file in another folder and leaves both alone`() {
        val theirs = (outside / "theirs.txt").writeRaw("dotfile")
        val link = (dir / "a.txt").linkTo(theirs)
        val writer = dir.writer().assertSuccess()

        val refused = Err(FileError.NotRegularFile(writer.root / "a.txt", FileType.Symlink))
        assertEquals(refused, writer.writeBytes("a.txt", "mine".encodeToByteArray(), inPlace = true))

        assertEquals(Ok(theirs), link.readSymlink())
        assertEquals("dotfile", theirs.readRaw())
    }

    @Test
    fun `a writer write with replaceLink puts a regular file in place of a link and leaves the file it led to alone`() {
        val theirs = (outside / "theirs.txt").writeRaw("theirs")
        val link = (dir / "a.txt").linkTo(theirs)

        assertEquals(
            Ok(WriteWay.Renamed),
            dir.writer().assertSuccess().writeText("a.txt", "mine", replaceLink = true).way(),
        )

        assertEquals(Ok(false), link.isSymlink())
        assertEquals("mine", link.readRaw())
        assertEquals("theirs", theirs.readRaw())
        assertEquals(listOf(link), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a writer write with replaceLink onto a dangling link replaces the link and creates nothing where it led`() {
        val link = (dir / "a.txt").linkTo(outside / "missing" / "theirs.txt")

        assertEquals(
            Ok(WriteWay.Renamed),
            dir.writer().assertSuccess().writeText("a.txt", "mine", replaceLink = true).way(),
        )

        assertEquals("mine", link.readRaw())
        assertFalse(SystemFileSystem.exists(outside / "missing"))
    }

    @Test
    fun `a writer write with replaceLink onto a link to a folder replaces the link and leaves the folder alone`() {
        val folder = outside / "folder"
        createDirectoriesRaw(folder)
        val link = (dir / "a.txt").linkTo(folder)

        assertEquals(
            Ok(WriteWay.Renamed),
            dir.writer().assertSuccess().writeText("a.txt", "mine", replaceLink = true).way(),
        )

        assertEquals("mine", link.readRaw())
        assertEquals(Ok(FileType.Directory), folder.fileType())
    }

    @Test
    fun `a private writer with replaceLink makes the file 600 and leaves the mode of the file the link led to`() {
        val theirs = (outside / "theirs.txt").writeRaw("theirs")
        val before = theirs.permissions()
        val link = (dir / "a.txt").linkTo(theirs)
        dir.chmod(OWNER_ONLY_FOLDER).assertSuccess()

        dir.privateWriter().assertSuccess().writeText("a.txt", "mine", replaceLink = true).assertSuccess()

        link.assertPermissions(0b110_000_000)
        assertEquals(before, theirs.permissions())
    }

    @Test
    fun `a writer copy with replaceLink onto a link to the source itself replaces the link with a copy`() {
        val source = (dir / "source.txt").writeRaw("content")
        val link = (dir / "copy.txt").linkTo(source)

        assertEquals(
            Ok(WriteWay.Renamed),
            dir.writer().assertSuccess().copyFrom(source, "copy.txt", replaceLink = true).way(),
        )

        assertEquals(Ok(false), link.isSymlink())
        assertEquals("content", link.readRaw())
        assertEquals("content", source.readRaw())
    }
}
