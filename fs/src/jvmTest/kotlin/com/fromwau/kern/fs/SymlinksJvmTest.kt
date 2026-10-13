package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymlinksJvmTest {
    private val dir = newTempDir()
    private val links = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        // Removed first, so clearing the tree never follows one back into it.
        links.forEach { Files.delete(Paths.get(it.toString())) }
        dir.deleteTree()
    }

    private fun Path.linkTo(target: Path): Path = also {
        Files.createSymbolicLink(Paths.get(toString()), Paths.get(target.toString()))
        links += it
    }

    @Test
    fun `a walk does not enter a linked folder by default but yields a linked file`() {
        val elsewhere = newTempDir()
        (elsewhere / "inside.txt").writeRaw("i")
        (dir / "folder").linkTo(elsewhere)
        val linkedFile = (dir / "file").linkTo((elsewhere / "target.txt").writeRaw("t"))

        assertEquals(listOf(Ok(linkedFile)), dir.walkTopDown().toList())
    }

    @Test
    fun `a walk that follows links enters a linked folder`() {
        val elsewhere = newTempDir()
        (elsewhere / "inside.txt").writeRaw("i")
        (dir / "folder").linkTo(elsewhere)

        assertEquals(listOf(Ok(dir / "folder" / "inside.txt")), dir.walkTopDown(Symlinks.Follow).toList())
    }

    @Test
    fun `a walk that follows links passes over a link back to a folder it has entered`() {
        val sub = dir / "sub"
        SystemFileSystem.createDirectories(sub)
        val file = (sub / "a.txt").writeRaw("a")
        (sub / "up").linkTo(dir)

        assertEquals(listOf(Ok(file)), dir.walkTopDown(Symlinks.Follow).toList())
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
        SystemFileSystem.createDirectories(outside)
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
        SystemFileSystem.createDirectories(outside)
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
}
