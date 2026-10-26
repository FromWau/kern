package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SymlinksJvmTest {
    private val dir = newTempDir()
    private val elsewhere = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
        elsewhere.deleteTree()
    }

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    @Test
    fun `a walk reads no file through a link leading out of the root by default`() {
        (elsewhere / "inside.txt").writeRaw("i")
        (dir / "folder").linkTo(elsewhere)
        (dir / "file").linkTo((elsewhere / "target.txt").writeRaw("t"))

        assertEquals(emptyList(), dir.walkTopDown().assertSuccess().walkedFiles())
    }

    @Test
    fun `a walk that follows links enters a linked folder`() {
        (elsewhere / "inside.txt").writeRaw("i")
        (dir / "folder").linkTo(elsewhere)

        val walked = dir.walkTopDown(Symlinks.Anywhere).assertSuccess().walkedFiles()

        assertEquals(listOf(Ok(dir / "folder" / "inside.txt")), walked)
    }

    @Test
    fun `a walk that follows links passes over a link back to a folder it has entered`() {
        val sub = dir / "sub"
        createDirectoriesRaw(sub)
        val file = (sub / "a.txt").writeRaw("a")
        (sub / "up").linkTo(dir)

        assertEquals(listOf(Ok(file)), dir.walkTopDown(Symlinks.Anywhere).assertSuccess().walkedFiles())
    }

    @Test
    fun `delete removes a symlink whose target is missing`() {
        val dangling = dir / "dangling"
        Files.createSymbolicLink(Paths.get(dangling.toString()), Paths.get("missing"))

        assertEquals(Ok(Unit), dangling.delete())
        assertEquals(false, Files.isSymbolicLink(Paths.get(dangling.toString())))
    }

    @Test
    fun `delete removes a link to a folder with entries and leaves the folder whole`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val link = dir / "link"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(outside.toString()))

        assertEquals(Ok(Unit), link.delete())
        assertFalse(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `deleteRecursively removes a link to a folder with entries and leaves the folder whole`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val link = dir / "link"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(outside.toString()))

        assertEquals(Ok(Unit), link.deleteRecursively())
        assertFalse(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `delete of a name the platform cannot parse is an error rather than a throw`() {
        // A NUL byte is the one name java.nio refuses to parse at all.
        val unparsable = dir / ("a" + Char(0) + "b")

        assertTrue(unparsable.delete() is Result.Error)
    }

    @Test
    fun `isSymlink tells a link from what it points at even when that is missing`() {
        val real = (dir / "real.toml").writeRaw("a")
        val link = (dir / "link.toml").linkTo(real)
        val dangling = (dir / "dangling.toml").linkTo(dir / "missing.toml")

        assertEquals(Ok(true), link.isSymlink())
        assertEquals(Ok(true), dangling.isSymlink())
        assertEquals(Ok(false), real.isSymlink())
        assertEquals(Ok(false), dir.isSymlink())
    }


    @Test
    fun `realPath resolves every link on the way`() {
        val real = (dir / "real.toml").writeRaw("a")
        val second = (dir / "second.toml").linkTo(real)
        val first = (dir / "first.toml").linkTo(second)

        assertEquals(Ok(SystemFileSystem.resolve(real)), first.realPath())
    }

    @Test
    fun `realPath of a dangling link is NotFound`() {
        val dangling = (dir / "dangling.toml").linkTo(dir / "missing.toml")

        assertEquals(Err(FileError.NotFound(dangling)), dangling.realPath())
    }

    @Test
    fun `a walk yields a FIFO and a dangling symlink as skipped where they stand`() {
        val file = (dir / "a.txt").writeRaw("a")
        val fifo = dir / "pipe"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")
        val dangling = (dir / "dangling").linkTo(dir / "missing")

        val expected = listOf(
            walkedFile(file, Path("a.txt"), size = 1, viaLink = false),
            WalkEntry.Skipped(dangling, Path("dangling"), WalkSkip.DanglingLink),
            WalkEntry.Skipped(fifo, Path("pipe"), WalkSkip.NotFileOrFolder(FileType.Fifo)),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }
}
