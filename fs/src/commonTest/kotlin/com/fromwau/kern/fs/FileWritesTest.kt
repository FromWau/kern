package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

class FileWritesTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** This path's permission bits, or null where the platform has none. */
    private fun Path.bits(): Int? = permissions().assertSuccess()?.bits

    @Test
    fun `writeText with modifiedAt gives the file that time in every mode`() {
        val time = Instant.parse("2001-02-03T04:05:06Z")

        for (mode in listOf(WriteMode.Auto, WriteMode.Atomic, WriteMode.Replace, WriteMode.InPlace)) {
            val file = dir / "page-$mode.html"
            file.writeText("old", mode).assertSuccess()
            file.writeText("new", mode, modifiedAt = time).assertSuccess()

            assertEquals(time, file.info().assertSuccess().modifiedAt, "$mode")
            assertEquals("new", file.readRaw(), "$mode")
        }
    }

    @Test
    fun `writeBytes with modifiedAt gives a new file that time`() {
        val time = Instant.parse("2001-02-03T04:05:06Z")
        val file = dir / "a.bin"

        file.writeBytes(byteArrayOf(1), modifiedAt = time).assertSuccess()

        assertEquals(time, file.info().assertSuccess().modifiedAt)
    }

    @Test
    fun `executable gives a new file an execute bit for each class that may read it`() {
        assertEquals(Ok(WriteWay.Renamed), (dir / "plain.sh").writeText("plain").way())
        val plain = (dir / "plain.sh").bits() ?: return
        val expected = plain or ((plain and 0b100_100_100) shr 2)

        for (mode in listOf(WriteMode.Auto, WriteMode.Atomic, WriteMode.Replace, WriteMode.InPlace)) {
            val file = dir / "run-$mode.sh"
            file.writeText("#!/bin/sh", mode, executable = true).assertSuccess()

            assertEquals(expected, file.bits(), "$mode")
            assertEquals("#!/bin/sh", file.readRaw())
        }
    }

    @Test
    fun `executable adds an execute bit for each class that may read an existing file in every mode`() {
        for (mode in listOf(WriteMode.Auto, WriteMode.Atomic, WriteMode.Replace, WriteMode.InPlace)) {
            val file = (dir / "run-$mode.sh").writeRaw("old")
            setFilePermissions(file, 0b110_100_000)
            if (file.bits() == null) return

            file.writeBytes("new".encodeToByteArray(), mode, executable = true).assertSuccess()

            assertEquals(0b111_101_000, file.bits(), "$mode")
            assertEquals("new", file.readRaw())
        }
    }

    @Test
    fun `executable with ownerOnly leaves a new or an existing file 700`() {
        val existing = (dir / "old.sh").writeRaw("old")
        setFilePermissions(existing, 0b110_100_100)
        if (existing.bits() == null) return

        existing.writeText("new", ownerOnly = true, executable = true).assertSuccess()
        (dir / "new.sh").writeText("new", WriteMode.InPlace, ownerOnly = true, executable = true).assertSuccess()

        assertEquals(0b111_000_000, existing.bits())
        assertEquals(0b111_000_000, (dir / "new.sh").bits())
    }

    @Test
    fun `a write without executable keeps an existing file's execute bits`() {
        val file = (dir / "run.sh").writeRaw("old")
        setFilePermissions(file, 0b111_101_101)
        if (file.bits() == null) return

        file.writeText("new").assertSuccess()
        file.writeText("newer", WriteMode.InPlace).assertSuccess()

        assertEquals(0b111_101_101, file.bits())
    }

    @Test
    fun `copyTo with executable adds the execute bits to the mode keepAttributes keeps`() {
        val source = (dir / "source.sh").writeRaw("content")
        setFilePermissions(source, 0b110_100_000)
        if (source.bits() == null) return

        source.copyTo(dir / "kept.sh", executable = true, keepAttributes = true).assertSuccess()
        source.copyTo(dir / "plain.sh", WriteMode.InPlace, keepAttributes = true).assertSuccess()

        assertEquals(0b111_101_000, (dir / "kept.sh").bits())
        assertEquals(0b110_100_000, (dir / "plain.sh").bits())
        assertEquals(0b110_100_000, source.bits())
    }

    @Test
    fun `writeText creates the file`() {
        val file = dir / "a.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("a = 1").way())
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `writeText replaces the content and leaves no backup behind`() {
        val file = (dir / "a.toml").writeRaw("old content that is longer than the new one")

        file.writeText("new").assertSuccess()
        assertEquals("new", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an Auto write creates a new file and leaves nothing beside it`() {
        val file = dir / "a.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("a = 1", WriteMode.Auto).way())
        assertEquals("a = 1", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an Atomic write creates a new file and leaves nothing beside it`() {
        val file = dir / "a.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("a = 1", WriteMode.Atomic).way())
        assertEquals("a = 1", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a write in place into a folder that may not be written names the backup it was refused`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder)
        val file = (folder / "a.toml").writeRaw("old")
        val probe = { runCatching { SystemFileSystem.sink(folder / "probe").close() }.isSuccess }

        folder.withMode(0b101_000_000, stillWorks = probe) {
            val failure = file.writeText("new", WriteMode.InPlace).assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            val backup = assertIs<FailedAt.Backup>(failure.at).path
            assertEquals(folder, backup.parent)
            assertTrue(backup.name.startsWith(".a.toml.") && backup.name.endsWith(".bak.part"))
            assertEquals("old", file.readRaw())
        }
    }

    @Test
    fun `deleting a file or folder in a folder that may not be written is WriteFailed Denied`() {
        val folder = dir / "locked"
        createDirectoriesRaw(folder / "sub")
        val file = (folder / "a.toml").writeRaw("old")
        val probe = { runCatching { SystemFileSystem.sink(folder / "probe").close() }.isSuccess }

        folder.withMode(0b101_000_000, stillWorks = probe) {
            assertEquals(Err(FileError.WriteFailed(file, IoCause.Denied, null)), file.delete())
            assertEquals(Err(FileError.WriteFailed(folder / "sub", IoCause.Denied, null)), (folder / "sub").delete())
            assertEquals("old", file.readRaw())
        }
    }

    @Test
    fun `an InPlace write creates a new file and says InPlace with nothing beside it`() {
        val file = dir / "a.toml"

        assertEquals(Ok(WriteWay.InPlace), file.writeText("a = 1", WriteMode.InPlace).way())
        assertEquals("a = 1", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an Atomic write replaces the content and leaves nothing beside it`() {
        val file = (dir / "a.toml").writeRaw("old content that is longer than the new one")

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Atomic).way())
        assertEquals("new", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `an InPlace write replaces the content and leaves nothing beside it`() {
        val file = (dir / "a.toml").writeRaw("old content that is longer than the new one")

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", WriteMode.InPlace).way())
        assertEquals("new", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `writeText creates missing parent directories`() {
        val file = dir / "a" / "b" / "c.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("c").way())
        assertEquals("c", file.readRaw())
    }

    @Test
    fun `writeBytes writes the exact bytes`() {
        val file = dir / "a.bin"
        val bytes = byteArrayOf(0, 1, 0xFF.toByte())

        assertEquals(Ok(WriteWay.Renamed), file.writeBytes(bytes).way())
        val written = SystemFileSystem
            .source(file)
            .buffered()
            .use { it.readByteArray() }
        assertContentEquals(bytes, written)
    }

    @Test
    fun `writing onto a directory is NotRegularFile and changes nothing`() {
        val target = dir / "taken"
        createDirectoriesRaw(target)

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
    fun `createDirectories at or below a dangling symlink is WriteFailed naming the link and makes nothing`() {
        val link = links.make(dir / "link", dir / "missing")
        val refused = Err(FileError.WriteFailed(link, IoCause.Other, null))

        assertEquals(refused, link.createDirectories())
        assertEquals(refused, (link / "a").createDirectories())
        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").info())
    }

    @Test
    fun `createDirectories with ownerOnly at a dangling symlink fails with the cause it has without`() {
        val link = links.make(dir / "link", dir / "missing")

        val plain = assertIs<FileError.WriteFailed>(link.createDirectories().assertError())
        val ownerOnly = assertIs<FileError.WriteFailed>(link.createDirectories(ownerOnly = true).assertError())

        assertEquals(plain, ownerOnly)
        assertEquals(plain.exception?.let { it::class }, assertNotNull(ownerOnly.exception)::class)
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
    fun `moveTo of a missing path creates none of the folders the target would need`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.moveTo(dir / "new" / "sub" / "x.txt"))
        assertEquals(emptyList<Path>(), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `moveTo creates the folders the target needs`() {
        val from = (dir / "a.txt").writeRaw("a")
        val to = dir / "new" / "deeper" / "b.txt"

        assertEquals(Ok(Unit), from.moveTo(to))

        assertFalse(from.exists())
        assertEquals("a", to.readRaw())
    }

    @Test
    fun `moveTo below a file is NotADirectory naming that file and leaves the source`() {
        val from = (dir / "a.txt").writeRaw("a")
        val file = (dir / "file").writeRaw("f")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), from.moveTo(file / "b.txt"))
        assertEquals("a", from.readRaw())
    }

    @Test
    fun `moveTo of a file onto an empty folder is NotRegularFile naming the folder and moves nothing`() {
        val from = (dir / "a.txt").writeRaw("a")
        val folder = dir / "folder"
        createDirectoriesRaw(folder)

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), from.moveTo(folder))
        assertEquals("a", from.readRaw())
        assertEquals(emptyList<Path>(), SystemFileSystem.list(folder).toList())
    }

    @Test
    fun `moveTo of a file onto a folder with entries is NotRegularFile naming the folder`() {
        val from = (dir / "a.txt").writeRaw("a")
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        (folder / "entry").writeRaw("e")

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), from.moveTo(folder))
        assertEquals("a", from.readRaw())
    }

    @Test
    fun `moveTo of a folder onto a file is NotADirectory naming the file and moves nothing`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        (folder / "entry").writeRaw("e")
        val file = (dir / "file").writeRaw("f")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), folder.moveTo(file))
        assertEquals("e", (folder / "entry").readRaw())
        assertEquals("f", file.readRaw())
    }

    @Test
    fun `moveTo of a folder onto a dangling symlink is NotADirectory naming the link and moves nothing`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        val link = links.make(dir / "link", dir / "missing")

        assertEquals(Err(FileError.NotADirectory(link, FileType.Symlink)), folder.moveTo(link))
        assertTrue(SystemFileSystem.exists(folder))
        assertEquals(Ok(dir / "missing"), link.readSymlink())
    }

    @Test
    fun `moveTo of a folder onto a symlink to a folder is NotADirectory naming the link`() {
        val folder = dir / "folder"
        val other = dir / "other"
        createDirectoriesRaw(folder)
        createDirectoriesRaw(other)
        val link = links.make(dir / "link", other)

        assertEquals(Err(FileError.NotADirectory(link, FileType.Symlink)), folder.moveTo(link))
        assertTrue(SystemFileSystem.exists(folder))
        assertEquals(Ok(other), link.readSymlink())
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
        createDirectoriesRaw(empty)

        assertEquals(Ok(Unit), empty.delete())
        assertFalse(empty.exists())
    }

    @Test
    fun `delete refuses a directory that still holds entries`() {
        val outer = dir / "outer"
        createDirectoriesRaw(outer)
        val inner = (outer / "inner.txt").writeRaw("a")

        val error = outer.delete().assertError<FileError.WriteFailed>()
        assertEquals(outer, error.path)
        assertEquals(IoCause.Other, error.cause)
        assertNotNull(error.exception)
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
        createDirectoriesRaw(outer / "inner")
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
    fun `deleteRecursively of a path nothing is at is done already`() {
        assertEquals(Ok(Unit), (dir / "missing").deleteRecursively())
    }

    @Test
    fun `deleteRecursively of a path below a missing folder is done already`() {
        assertEquals(Ok(Unit), (dir / "missing" / "deeper").deleteRecursively())
    }

    @Test
    fun `a path ending in a dot names its folder for delete and deleteRecursively`() {
        val empty = dir / "empty"
        createDirectoriesRaw(empty)
        val tree = dir / "tree"
        createDirectoriesRaw(tree / "inner")
        (tree / "inner" / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), (empty / ".").delete())
        assertEquals(Ok(Unit), (tree / "." / ".").deleteRecursively())
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    @Test
    fun `a Replace write creates a new file and leaves nothing beside it`() {
        val file = dir / "a.toml"

        assertEquals(Ok(WriteWay.Renamed), file.writeText("a = 1", WriteMode.Replace).way())
        assertEquals("a = 1", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a Replace write replaces the content and leaves nothing beside it`() {
        val file = (dir / "a.toml").writeRaw("old content that is longer than the new one")

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Replace).way())
        assertEquals("new", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `copyTo copies the content to a new file and the folders it needs`() {
        val source = (dir / "a.txt").writeRaw("content")
        val target = dir / "copies" / "b.txt"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())

        assertEquals("content", target.readRaw())
        assertEquals("content", source.readRaw())
    }

    @Test
    fun `copyTo replaces what the target held and leaves nothing beside it`() {
        val source = (dir / "a.txt").writeRaw("new")
        val target = (dir / "b.txt").writeRaw("old content that is longer than the new one")

        assertEquals(Ok(WriteWay.InPlace), source.copyTo(target, WriteMode.InPlace).way())

        assertEquals("new", target.readRaw())
        assertEquals(setOf(source, target), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `copyTo copies every byte of a file larger than one buffer`() {
        val bytes = ByteArray(1_000_000) { (it % 251).toByte() }
        val source = dir / "big.bin"
        SystemFileSystem
            .sink(source)
            .buffered()
            .use { it.write(bytes) }
        val target = dir / "copy.bin"

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target).way())

        val copied = SystemFileSystem
            .source(target)
            .buffered()
            .use { it.readByteArray() }
        assertContentEquals(bytes, copied)
    }

    @Test
    fun `copyTo of a missing source is NotFound naming it and creates nothing`() {
        val missing = dir / "missing"
        val target = dir / "copies" / "b.txt"

        assertEquals(Err(FileError.NotFound(missing)), missing.copyTo(target))
        assertEquals(emptyList<Path>(), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `copyTo onto itself keeps the content and says Unchanged`() {
        val file = (dir / "a.txt").writeRaw("content")

        assertIs<Copied.Unchanged>(file.copyTo(file).assertSuccess())
        assertEquals("content", file.readRaw())
    }

    @Test
    fun `an InPlace copyTo onto itself keeps the content`() {
        val file = (dir / "a.txt").writeRaw("content")

        assertIs<Copied.Unchanged>(file.copyTo(file, WriteMode.InPlace).assertSuccess())
        assertEquals("content", file.readRaw())
    }

    @Test
    fun `opening a missing file to copy it is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.openSource())
    }

    @Test
    fun `a source that fails to close after being read whole still copies and does not throw`() {
        val target = dir / "b.txt"

        assertEquals(Ok(WriteWay.Renamed), target.writeFrom(UnclosableSource("content"), WriteMode.Auto).way())
        assertEquals("content", target.readRaw())
    }

    @Test
    fun `copyTo of a folder is NotRegularFile naming it`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), folder.copyTo(dir / "b.txt"))
    }

    @Test
    fun `copyTo onto a folder is NotRegularFile naming the target`() {
        val source = (dir / "a.txt").writeRaw("a")
        val folder = dir / "folder"
        createDirectoriesRaw(folder)

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), source.copyTo(folder))
    }

    @Test
    fun `a write that succeeds leaves no temp file behind`() {
        val target = dir / "kept.toml"
        assertEquals(Ok(WriteWay.Renamed), target.writeText("a = 1", WriteMode.Atomic).way())
        assertEquals(Ok(WriteWay.Renamed), target.writeText("a = 2", WriteMode.Atomic).way())

        assertEquals(Ok(emptyList()), target.leftoverCopies<Leftover.Temp>())
    }

    @Test
    fun `a temp name carrying a write id is reported as a leftover temp and not as a backup`() {
        val target = (dir / "cut.toml").writeRaw("a = 1")
        val second = (dir / ".cut.toml.ffffffff-1f4e-4d2b-9c80-7e15b2c4d9f1.tmp").writeRaw("a = 3")
        val first = (dir / ".cut.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        (dir / ".cut.toml.$A_WRITE_ID.bak").writeRaw("a = 0")

        assertEquals(Ok(listOf(first, second)), target.leftoverCopies<Leftover.Temp>())
        assertEquals(Ok(listOf(dir / ".cut.toml.$A_WRITE_ID.bak")), target.leftoverCopies<Leftover.Backup>())
    }

    @Test
    fun `a temp file kept under a name of someone's own is not a leftover temp`() {
        val target = (dir / "held.toml").writeRaw("a = 1")
        (dir / ".held.toml.draft.tmp").writeRaw("a = 1")
        (dir / ".held.toml.tmp").writeRaw("a = 1")
        (dir / ".other.toml.$A_WRITE_ID.tmp").writeRaw("a = 1")

        assertEquals(Ok(emptyList()), target.leftoverCopies<Leftover.Temp>())
    }

    @Test
    fun `leftovers reports the folder it could not read`() {
        val missing = dir / "nope"

        assertEquals(Err(FileError.NotFound(missing)), (missing / "app.toml").leftovers())
    }

    @Test
    fun `a write that succeeds leaves no backup behind`() {
        val target = dir / "kept.toml"
        assertEquals(Ok(WriteWay.InPlace), target.writeText("a = 1", WriteMode.InPlace).way())
        assertEquals(Ok(WriteWay.InPlace), target.writeText("a = 2", WriteMode.InPlace).way())

        assertEquals(Ok(emptyList()), target.leftoverCopies<Leftover.Backup>())
    }

    @Test
    fun `a name carrying a write id is reported as a leftover backup`() {
        val target = (dir / "cut.toml").writeRaw("a = 1")
        val leftover = (dir / ".cut.toml.$A_WRITE_ID.bak").writeRaw("a = 1\nb = 2")

        assertEquals(Ok(listOf(leftover)), target.leftoverCopies<Leftover.Backup>())
    }

    @Test
    fun `a copy kept beside the file under a name of someone's own is not a leftover backup`() {
        // Keeping one before an edit is an ordinary habit, and it must not read as a write that died.
        val target = (dir / "held.toml").writeRaw("a = 1")
        (dir / ".held.toml.before-my-edit.bak").writeRaw("a = 1")
        (dir / "held.toml.bak").writeRaw("a = 1")
        (dir / ".held.toml.bak").writeRaw("a = 1")

        assertEquals(Ok(emptyList()), target.leftoverCopies<Leftover.Backup>())
    }

    @Test
    fun `an undo of a write that made the file removes what it left`() {
        val target = (dir / "fresh.toml").writeRaw("half")

        assertNull(undoFailure(Undo.Remove, PathFolder(target.parent), target.name))

        assertFalse(SystemFileSystem.exists(target))
    }

    @Test
    fun `an undo whose backup cannot be read fails and leaves the file alone`() {
        val target = (dir / "gone.toml").writeRaw("a = 1")
        val backupPath = dir / ".gone.toml.$A_WRITE_ID.bak"

        val failure = undoFailure(Undo.Restore(backupPath, UnreadableFile), PathFolder(target.parent), target.name)

        assertNotNull(failure)
        assertEquals("a = 1", target.readRaw())
    }

    @Test
    fun `a backup of another file in the same folder is not a leftover of this one`() {
        val target = (dir / "a.toml").writeRaw("a = 1")
        (dir / "b.toml").writeRaw("b = 1")
        (dir / ".b.toml.$A_WRITE_ID.bak").writeRaw("b = 2")

        assertEquals(Ok(emptyList()), target.leftoverCopies<Leftover.Backup>())
    }

    @Test
    fun `an undo that has the backup to put back reports nothing`() {
        val target = (dir / "back.toml").writeRaw("old")
        val backupPath = dir / ".back.toml.$A_WRITE_ID.bak"
        val backup = copyFile(from = target, to = backupPath)
        target.writeRaw("new")

        assertNull(undoFailure(Undo.Restore(backupPath, backup), PathFolder(target.parent), target.name))

        assertEquals("old", target.readRaw())
        assertFalse(SystemFileSystem.exists(backupPath))
    }

    @Test
    fun `a held file reads back from its first byte what was written through it`() {
        val path = dir / "held.toml"

        val read = createHeldFile(path).use { held ->
            held
                .sink()
                .buffered()
                .use { it.writeString("a = 1") }
            held.sync()
            held
                .source()
                .buffered()
                .use { it.readString() }
        }

        assertEquals("a = 1", read)
        assertEquals("a = 1", path.readRaw())
    }

    @Test
    fun `a held file is never made over a file already at its path`() {
        val path = (dir / ".a.toml.$A_WRITE_ID.tmp").writeRaw("someone's own")

        assertFailsWith<IOException> { createHeldFile(path).close() }

        assertEquals("someone's own", path.readRaw())
    }

    /** [file] with a second name [other], a hard link to it. */
    private fun hardLinked(
        file: Path,
        other: Path,
    ): Path = other.also { it.hardLinkTo(file).assertSuccess() }

    @Test
    fun `an Auto write onto a hard linked file says InPlace and the other name sees it`() {
        val file = (dir / "a.toml").writeRaw("old")
        val other = hardLinked(file, dir / "b.toml")
        val before = file.fileKey()

        assertEquals(Ok(WriteWay.InPlace), file.writeText("new", WriteMode.Auto).way())

        assertEquals(before, file.fileKey())
        assertEquals("new", other.readRaw())
    }

    @Test
    fun `an Auto or InPlace write onto a hard linked file writes in place`() {
        val out = dir / "out"
        createDirectoriesRaw(out)
        val outside = (dir / "elsewhere.txt").writeRaw("old")
        val inside = hardLinked(outside, out / "x")

        assertEquals(Ok(WriteWay.InPlace), inside.writeText("auto").way())
        assertEquals("auto", outside.readRaw())
        assertEquals(Ok(WriteWay.InPlace), inside.writeText("asked", WriteMode.InPlace).way())
        assertEquals("asked", outside.readRaw())
        assertEquals(outside.fileKey(), inside.fileKey())
    }

    @Test
    fun `copyTo onto itself leaves the file as it was and says Unchanged`() {
        val file = (dir / "a.toml").writeRaw("content")
        val before = file.fileKey()

        assertIs<Copied.Unchanged>(file.copyTo(file).assertSuccess())

        assertEquals(before, file.fileKey())
        assertEquals("content", file.readRaw())
    }

    @Test
    fun `copyTo onto a hard link of itself keeps the content`() {
        val file = (dir / "a.toml").writeRaw("content")
        val other = hardLinked(file, dir / "b.toml")

        assertIs<Copied.Unchanged>(file.copyTo(other).assertSuccess())
        assertIs<Copied.Unchanged>(file.copyTo(other, WriteMode.InPlace).assertSuccess())

        assertEquals("content", file.readRaw())
        assertEquals(file.fileKey(), other.fileKey())
    }

    @Test
    fun `an Atomic write onto a hard linked file is refused and changes neither name`() {
        val file = (dir / "a.toml").writeRaw("old")
        val other = hardLinked(file, dir / "b.toml")

        val refused = file.writeText("new", WriteMode.Atomic)

        assertEquals(Err(FileError.AtomicRefused(file, AtomicRefusal.OtherHardLinks)), refused)
        assertEquals("old", file.readRaw())
        assertEquals("old", other.readRaw())
        assertEquals(setOf(file, other), SystemFileSystem.list(dir).toSet())
    }

    @Test
    fun `a Replace write onto a hard linked file renames and leaves the other name the old content`() {
        val file = (dir / "a.toml").writeRaw("old")
        val other = hardLinked(file, dir / "b.toml")
        val before = file.fileKey()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Replace).way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.fileKey())
        assertEquals("old", other.readRaw())
    }

    @Test
    fun `a writer restore puts the backup back into the file itself so its other hard links see it`() {
        val file = (dir / "a.toml").writeRaw("half")
        val other = hardLinked(file, dir / "b.toml")
        val before = file.fileKey()
        val backup = (dir / ".a.toml.$A_WRITE_ID.bak").writeRaw("whole")

        assertEquals(Ok(Unit), dir.writer().assertSuccess().restore(Leftover.Backup(file, backup, A_WRITE_START)))

        assertEquals(before, file.fileKey())
        assertEquals("whole", other.readRaw())
        assertEquals(setOf(file, other), SystemFileSystem.list(dir).toSet())
    }
}

/** A backup whose content cannot be read back, as one on a failing device would be. */
private object UnreadableFile : HeldFile {
    override fun setPermissions(bits: Int) = Unit

    override fun sink(): RawSink = throw IOException("not writable")

    override fun source(): RawSource = throw IOException("not readable")

    override fun setModifiedTime(time: Instant) = Unit

    override fun moveTo(target: Path) = Unit

    override fun sync() = Unit

    override fun info(): FileInfo = throw IOException("not readable")

    override fun close() = Unit
}

/** Hands out [text] and then throws on close, as a source on a failing device can. */
private class UnclosableSource(text: String) : RawSource {
    private val content = Buffer().apply { writeString(text) }

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = content.readAtMostTo(sink, byteCount)

    override fun close(): Unit = throw IOException("close failed")
}
