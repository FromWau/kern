package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WalkTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `each directory reports its own files first and then walks its subdirectories in name order`() {
        SystemFileSystem.createDirectories(dir / "b" / "d")
        SystemFileSystem.createDirectories(dir / "e")
        (dir / "c.txt").writeRaw("c")
        (dir / "a.txt").writeRaw("a")
        (dir / "b" / "x.txt").writeRaw("x")
        (dir / "b" / "d" / "y.txt").writeRaw("y")

        val expected = listOf(
            dir / "a.txt",
            dir / "c.txt",
            dir / "b" / "x.txt",
            dir / "b" / "d" / "y.txt",
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().toList())
    }

    @Test
    fun `a regular file walks as itself`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(listOf(Ok(file)), file.walkTopDown().toList())
    }

    @Test
    fun `a missing root is NotFound`() {
        val missing = dir / "missing"

        assertEquals(listOf(Err(FileError.NotFound(missing))), missing.walkTopDown().toList())
    }
}
