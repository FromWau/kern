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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `createDirectories over a file is NotADirectory naming the file`() {
        val file = (dir / "a").writeRaw("a")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.createDirectories())
    }

    @Test
    fun `createDirectories below a file is NotADirectory naming the file`() {
        val file = (dir / "a").writeRaw("a")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), (file / "b" / "c").createDirectories())
    }

    @Test
    fun `moveTo moves a file and leaves nothing at the old path`() {
        val from = (dir / "a.txt").writeRaw("a")
        val to = dir / "b.txt"

        assertEquals(Ok(Unit), from.moveTo(to))

        assertFalse(from.exists())
        assertEquals(Ok("a"), to.readText())
    }

    @Test
    fun `moveTo replaces a file already at the target`() {
        val from = (dir / "a.txt").writeRaw("new")
        val to = (dir / "b.txt").writeRaw("old")

        assertEquals(Ok(Unit), from.moveTo(to))

        assertEquals(Ok("new"), to.readText())
    }

    @Test
    fun `moveTo of a missing path is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.moveTo(dir / "b.txt"))
    }

    @Test
    fun `moveTo into a folder that is not there is WriteFailed naming the target`() {
        val from = (dir / "a.txt").writeRaw("a")
        val to = dir / "nowhere" / "b.txt"

        val error = from.moveTo(to).errorOrNull()
        assertIs<FileError.WriteFailed>(error)
        assertEquals(to, error.path)
        assertTrue(from.exists())
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
        assertTrue(error.reason.contains("not empty", ignoreCase = true), error.reason)
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

    @Test
    fun `a write that succeeds leaves no backup behind`() {
        val target = dir / "kept.toml"
        assertEquals(Ok(Unit), target.writeText("a = 1"))
        assertEquals(Ok(Unit), target.writeText("a = 2"))

        assertEquals(Ok(emptyList()), target.leftoverBackups())
    }

    @Test
    fun `a name carrying a write id is reported as a leftover backup`() {
        val target = (dir / "cut.toml").writeRaw("a = 1")
        val leftover = (dir / ".cut.toml.$A_WRITE_ID.bak").writeRaw("a = 1\nb = 2")

        assertEquals(Ok(listOf(leftover)), target.leftoverBackups())
    }

    @Test
    fun `a copy kept beside the file under a name of someone's own is not a leftover backup`() {
        // Keeping one before an edit is an ordinary habit, and it must not read as a write that died.
        val target = (dir / "held.toml").writeRaw("a = 1")
        (dir / ".held.toml.before-my-edit.bak").writeRaw("a = 1")
        (dir / "held.toml.bak").writeRaw("a = 1")
        (dir / ".held.toml.bak").writeRaw("a = 1")

        assertEquals(Ok(emptyList()), target.leftoverBackups())
    }

    @Test
    fun `an undo of a write that made the file removes what it left`() {
        val target = (dir / "fresh.toml").writeRaw("half")

        assertNull(undoFailure(Undo.Remove, over = target))

        assertFalse(SystemFileSystem.exists(target))
    }

    @Test
    fun `leftoverBackups reports the folder it could not read`() {
        val missing = dir / "nope"

        assertEquals(Err(FileError.NotFound(missing)), (missing / "app.toml").leftoverBackups())
    }

    @Test
    fun `an undo with no backup to put back fails and leaves the file alone`() {
        val target = (dir / "gone.toml").writeRaw("a = 1")
        val absent = dir / ".gone.toml.$A_WRITE_ID.bak"

        val reason = undoFailure(Undo.Restore(absent), over = target)

        assertNotNull(reason)
        assertEquals("a = 1", target.readRaw())
    }

    @Test
    fun `a backup of another file in the same folder is not a leftover of this one`() {
        val target = (dir / "a.toml").writeRaw("a = 1")
        (dir / "b.toml").writeRaw("b = 1")
        (dir / ".b.toml.$A_WRITE_ID.bak").writeRaw("b = 2")

        assertEquals(Ok(emptyList()), target.leftoverBackups())
    }

    @Test
    fun `an undo that has the backup to put back reports nothing`() {
        val target = (dir / "back.toml").writeRaw("new")
        val backup = (dir / ".back.toml.$A_WRITE_ID.bak").writeRaw("old")

        assertNull(undoFailure(Undo.Restore(backup), over = target))

        assertEquals("old", target.readRaw())
        assertFalse(SystemFileSystem.exists(backup))
    }
}
