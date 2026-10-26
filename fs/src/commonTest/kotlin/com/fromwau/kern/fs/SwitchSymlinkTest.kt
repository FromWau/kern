package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class SwitchSymlinkTest {
    private val dir = newTempDir()
    private val links = TestLinks()
    private val current = dir / "current"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @Test
    fun `switchSymlinkTo points the link at the new target and leaves nothing else`() {
        createDirectoriesRaw(dir / "release-1")
        createDirectoriesRaw(dir / "release-2")
        links.make(current, Path("release-1"))

        assertEquals(Ok(Unit), current.switchSymlinkTo(Path("release-2")))

        assertEquals(Ok(Path("release-2")), current.readSymlink())
        assertEquals(setOf(current, dir / "release-1", dir / "release-2"), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `switchSymlinkTo points the link at a target that does not exist yet`() {
        createDirectoriesRaw(dir / "release-1")
        links.make(current, Path("release-1"))

        assertEquals(Ok(Unit), current.switchSymlinkTo(Path("release-2")))

        assertEquals(Ok(Path("release-2")), current.readSymlink())
        assertEquals(setOf(current, dir / "release-1"), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `switchSymlinkTo makes the link when none is there and its missing folders`() {
        val link = dir / "a" / "current"

        assertEquals(Ok(Unit), link.switchSymlinkTo(Path("release-1")))

        assertEquals(Ok(Path("release-1")), link.readSymlink())
        link.delete().assertSuccess()
    }

    @Test
    fun `a new link that fails to move into place is WriteFailed at it and is removed`() {
        createDirectoriesRaw(dir / "release-1")
        links.make(current, Path("release-1"))
        var staged: Path? = null

        val switched = current.switchSymlinkWith(Path("release-2")) { link, _ ->
            staged = link
            IOException("refused")
        }

        val at = FailedAt.Temp(checkNotNull(staged))
        assertEquals(Err(writeFailed(current, IOException("refused"), at = at)), switched)
        assertEquals(Ok(Path("release-1")), current.readSymlink())
        assertEquals(setOf(current, dir / "release-1"), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `switchSymlinkTo onto a real folder is NotRegularFile and leaves no staged link`() {
        createDirectoriesRaw(current)

        assertEquals(Err(FileError.NotRegularFile(current, FileType.Directory)), current.switchSymlinkTo(Path("x")))

        assertEquals(listOf(current), SystemFileSystem.list(dir))
    }

    @Test
    fun `switchSymlinkTo onto a regular file is AlreadyExists and leaves the file as it was`() {
        current.writeRaw("kept")

        assertEquals(Err(FileError.AlreadyExists(current, FileType.Regular)), current.switchSymlinkTo(Path("x")))

        assertEquals("kept", current.readRaw())
        assertEquals(listOf(current), SystemFileSystem.list(dir))
    }

    @Test
    fun `switchSymlinkTo of a name too long for its staged link is NameTooLong and makes nothing`() {
        val long = dir / "a".repeat(213)

        assertEquals(Err(FileError.NameTooLong(long, LongName.StagedLink, 256)), long.switchSymlinkTo(Path("x")))
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    @Test
    fun `switchSymlinkTo takes the longest name its staged link fits`() {
        val long = dir / "a".repeat(212)

        assertEquals(Ok(Unit), long.switchSymlinkTo(Path("x")))

        assertEquals(Ok(Path("x")), long.readSymlink())
        long.delete().assertSuccess()
    }

    @Test
    fun `a staged link left beside a link is a StagedLink that a writer discards without its target`() {
        val target = (dir / "release-1").writeRaw("x")
        val staged = links.make(dir / ".current.$A_WRITE_ID.link", Path("release-1"))
        val expected = Leftover.StagedLink(file = current, copy = staged, startedAt = A_WRITE_START)

        assertEquals(listOf<Leftover>(expected), dir.leftoversIn().assertSuccess().found)
        assertEquals(Ok(listOf<Leftover>(expected)), current.leftovers())
        assertEquals(Ok(Unit), dir.writer().assertSuccess().discard(expected))
        assertEquals(listOf(target), SystemFileSystem.list(dir))
        assertEquals("x", target.readRaw())
    }
}
