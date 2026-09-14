package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileWritesTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `writeText creates the file`() {
        val file = dir / "a.toml"

        assertEquals(Ok(Unit), file.writeText("a = 1"))
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `writeText replaces the content and leaves no backup behind`() {
        val file = (dir / "a.toml").writeRaw("old content that is longer than the new one")

        assertEquals(Ok(Unit), file.writeText("new"))
        assertEquals("new", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `writeText creates missing parent directories`() {
        val file = dir / "a" / "b" / "c.toml"

        assertEquals(Ok(Unit), file.writeText("c"))
        assertEquals("c", file.readRaw())
    }

    @Test
    fun `writeBytes writes the exact bytes`() {
        val file = dir / "a.bin"
        val bytes = byteArrayOf(0, 1, 0xFF.toByte())

        assertEquals(Ok(Unit), file.writeBytes(bytes))
        val written = SystemFileSystem
            .source(file)
            .buffered()
            .use { it.readByteArray() }
        assertContentEquals(bytes, written)
    }

    @Test
    fun `writing onto a directory is NotRegularFile and changes nothing`() {
        val target = dir / "taken"
        SystemFileSystem.createDirectories(target)

        assertEquals(Err(FileError.NotRegularFile(target, FileType.Directory)), target.writeText("new"))
        assertEquals(listOf(target), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `createDirectories creates missing parents and accepts an existing directory`() {
        val nested = dir / "a" / "b"

        assertEquals(Ok(Unit), nested.createDirectories())
        assertEquals(Ok(Unit), nested.createDirectories())
        assertTrue(SystemFileSystem.metadataOrNull(nested)?.isDirectory == true)
    }

    @Test
    fun `createDirectories fails naming a file that stands at the path`() {
        val file = (dir / "a").writeRaw("a")

        val error = file.createDirectories().errorOrNull()
        assertIs<FileError.WriteFailed>(error)
        assertEquals(file, error.path)
    }

    @Test
    fun `createDirectories fails naming a file that stands in place of a parent`() {
        val file = (dir / "a").writeRaw("a")

        val error = (file / "b" / "c").createDirectories().errorOrNull()
        assertIs<FileError.WriteFailed>(error)
        assertEquals(file, error.path)
    }

    @Test
    fun `delete removes a file`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), file.delete())
        assertFalse(file.exists())
    }

    @Test
    fun `delete removes an empty directory`() {
        val empty = dir / "empty"
        SystemFileSystem.createDirectories(empty)

        assertEquals(Ok(Unit), empty.delete())
        assertFalse(empty.exists())
    }

    @Test
    fun `delete refuses a directory that still holds entries`() {
        val outer = dir / "outer"
        SystemFileSystem.createDirectories(outer)
        val inner = (outer / "inner.txt").writeRaw("a")

        val error = outer.delete().errorOrNull()
        assertIs<FileError.WriteFailed>(error)
        assertEquals(outer, error.path)
        assertTrue(inner.exists())
    }

    @Test
    fun `delete reports a missing path as NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.delete())
    }

    @Test
    fun `deleteRecursively removes a whole tree`() {
        val outer = dir / "outer"
        SystemFileSystem.createDirectories(outer / "inner")
        (outer / "a.txt").writeRaw("a")
        (outer / "inner" / "b.txt").writeRaw("b")

        assertEquals(Ok(Unit), outer.deleteRecursively())
        assertFalse(outer.exists())
        assertTrue(dir.exists())
    }

    @Test
    fun `deleteRecursively removes a single file`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), file.deleteRecursively())
        assertFalse(file.exists())
    }
}
