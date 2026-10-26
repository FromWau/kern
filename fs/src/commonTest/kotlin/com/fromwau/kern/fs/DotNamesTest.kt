package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.SystemFileSystem

class DotNamesTest {
    private val dir = newTempDir()
    private val dst = (dir / "dst").also { createDirectoriesRaw(it) }

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a write of a path ending in dot dot below a folder is NotRegularFile and creates nothing`() {
        createDirectoriesRaw(dst / "a")
        val source = (dir / "source.toml").writeRaw("x")
        val target = dst / "a" / "ok" / ".."
        val refused = Err(FileError.NotRegularFile(target, FileType.Directory))

        assertEquals(refused, target.writeText("x"))
        assertEquals(refused, target.writeBytes(byteArrayOf(1), mode = WriteMode.InPlace))
        assertEquals(refused, source.copyTo(target))
        assertEquals(emptyList(), SystemFileSystem.list(dst / "a"))
    }

    @Test
    fun `a write of a path ending in a dot name where nothing is there is NotFound and creates nothing`() {
        val missing = dst / "new" / "x" / ".."
        val gone = dst / "gone" / "."
        val source = (dir / "source.toml").writeRaw("x")

        assertEquals(Err(FileError.NotFound(missing)), missing.writeText("x"))
        assertEquals(Err(FileError.NotFound(gone)), gone.writeText("x"))
        assertEquals(Err(FileError.NotFound(gone)), source.copyTo(gone))
        assertEquals(emptyList(), SystemFileSystem.list(dst))
    }

    @Test
    fun `a write of a path ending in a dot name that names a file is NotADirectory naming that file`() {
        val file = (dst / "notes.txt").writeRaw("kept")
        val throughFile = dst / "notes.txt" / "."
        val source = (dir / "source.toml").writeRaw("x")
        val refused = Err(FileError.NotADirectory(file, FileType.Regular))

        assertEquals(refused, throughFile.writeText("x"))
        assertEquals(refused, throughFile.writeBytes(byteArrayOf(1), mode = WriteMode.InPlace))
        assertEquals(refused, source.copyTo(throughFile))
        assertEquals("kept", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dst))
    }

    @Test
    fun `a delete of a path ending in dot dot removes the folder above`() {
        createDirectoriesRaw(dst / "tree" / "inner")
        (dst / "tree" / "inner" / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), (dst / "tree" / "inner" / "gone" / ".." / "..").deleteRecursively())
        assertEquals(emptyList(), SystemFileSystem.list(dst))
    }
}
