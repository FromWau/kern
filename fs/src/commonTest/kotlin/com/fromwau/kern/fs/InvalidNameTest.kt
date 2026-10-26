package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A native call reads a name only up to a NUL, so each path here would reach `new` if the platform saw it. */
class InvalidNameTest {
    private val dir = newTempDir()
    private val nul = dir / "new${Char(0)}x"
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun invalid(path: Path) = Err(FileError.InvalidName(path, NameProblem.Nul))

    private fun entries(): Set<Path> = SystemFileSystem.list(dir).toSet()

    @Test
    fun `a write to a path holding a NUL is InvalidName and creates nothing`() {
        val file = nul / "a.txt"

        assertEquals(invalid(file), file.writeText("x"))
        assertEquals(invalid(file), file.writeBytes(byteArrayOf(1), WriteMode.InPlace))
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `a writer on a folder whose path holds a NUL is InvalidName naming that folder`() {
        val folder = Path("$dir${Char(0)}")

        assertEquals(invalid(folder), folder.writer())
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `a read of a path holding a NUL is InvalidName and does not exist`() {
        (dir / "new").writeRaw("theirs")

        assertEquals(invalid(nul), nul.readText())
        assertEquals(invalid(nul), nul.fileType())
        assertFalse(nul.exists())
    }

    @Test
    fun `a copy onto a path holding a NUL is InvalidName and creates nothing`() {
        val source = (dir / "a.txt").writeRaw("a")
        val target = nul / "b.txt"

        assertEquals(invalid(target), source.copyTo(target))
        assertEquals(setOf(source), entries())
    }

    @Test
    fun `a copy onto a path holding a NUL that cuts it short to the source itself is InvalidName`() {
        val source = (dir / "new").writeRaw("a")

        assertEquals(invalid(nul), source.copyTo(nul))
    }

    @Test
    fun `a move from or to a path holding a NUL is InvalidName and moves nothing`() {
        val source = (dir / "a.txt").writeRaw("a")
        val new = links.make(dir / "new", Path("a.txt"))

        assertEquals(invalid(nul / "b.txt"), source.moveTo(nul / "b.txt"))
        assertEquals(invalid(nul), nul.moveTo(dir / "b.txt"))
        assertEquals(setOf(source, new), entries())
    }

    @Test
    fun `a symlink at or to a path holding a NUL is InvalidName and is not made`() {
        val target = Path("new${Char(0)}x")

        assertEquals(invalid(target), (dir / "link").createSymlink(target))
        assertEquals(invalid(nul), nul.createSymlink(Path("a.txt")))
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `isSymlink delete and deleteRecursively of a path holding a NUL are InvalidName and remove nothing`() {
        val target = (dir / "a.txt").writeRaw("theirs")
        val new = links.make(dir / "new", Path("a.txt"))

        assertEquals(invalid(nul), nul.isSymlink())
        assertEquals(invalid(nul), nul.delete())
        assertEquals(invalid(nul), nul.deleteRecursively())
        assertEquals(setOf(target, new), entries())
    }

    @Test
    fun `a walk from a path holding a NUL is InvalidName`() {
        createDirectoriesRaw(dir / "new")
        (dir / "new" / "a.txt").writeRaw("a")

        assertEquals(invalid(nul), nul.walkTopDown())
    }

    @Test
    fun `a lock on a path holding a NUL is InvalidName and creates nothing`() {
        val lockFile = nul / "app.lock"
        var ran = false

        assertEquals(invalid(lockFile), lockFile.tryLock())
        assertEquals(invalid(lockFile), lockFile.withLock(wait = Duration.ZERO) { ran = true })
        assertFalse(ran)
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `createDirectories and openAppend of a path holding a NUL are InvalidName and create nothing`() {
        assertEquals(invalid(nul / "sub"), (nul / "sub").createDirectories())
        assertEquals(invalid(nul / "app.log"), (nul / "app.log").openAppend())
        assertEquals(emptySet(), entries())
    }
}
