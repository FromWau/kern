package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileReadsTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `exists is true for a file and false for a missing path`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertTrue(file.exists())
        assertFalse((dir / "missing").exists())
    }

    @Test
    fun `readText returns the file content`() {
        val file = (dir / "a.txt").writeRaw("héllo")

        assertEquals(Ok("héllo"), file.readText())
    }

    @Test
    fun `readText of a missing file is NotFound`() {
        val file = dir / "missing"

        assertEquals(Err(FileError.NotFound(file)), file.readText())
    }

    @Test
    fun `readText of a directory is NotRegularFile`() {
        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readText())
    }

    @Test
    fun `readText refuses a file larger than the limit`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Err(FileError.TooLarge(file, limitBytes = 4, atLeastBytes = 5)), file.readText(maxBytes = 4))
    }

    @Test
    fun `readText accepts a file exactly at the limit`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Ok("12345"), file.readText(maxBytes = 5))
    }

    @Test
    fun `readText with the largest limit reads the whole file`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Ok("12345"), file.readText(maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `readText decodes bytes that are not UTF-8 as the replacement character`() {
        val file = dir / "a.bin"
        SystemFileSystem
            .sink(file)
            .buffered()
            .use { it.write(byteArrayOf(0x68, 0xFF.toByte(), 0x69)) }

        assertEquals(Ok("h�i"), file.readText())
    }

    @Test
    fun `list gives the entries in name order`() {
        (dir / "b.txt").writeRaw("b")
        (dir / "a.txt").writeRaw("a")
        SystemFileSystem.createDirectories(dir / "c")

        assertEquals(Ok(listOf(dir / "a.txt", dir / "b.txt", dir / "c")), dir.list())
    }

    @Test
    fun `list gives nothing for an empty directory`() {
        val empty = dir / "empty"
        SystemFileSystem.createDirectories(empty)

        assertEquals(Ok(emptyList<Path>()), empty.list())
    }

    @Test
    fun `list refuses a file`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.list())
    }

    @Test
    fun `a path that runs through a file is NotFound`() {
        val file = (dir / "plain.txt").writeRaw("x")
        val throughIt = file / "child.toml"

        assertEquals(Err(FileError.NotFound(throughIt)), throughIt.readText())
    }

    @Test
    fun `list reports a missing path as NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.list())
    }
}
