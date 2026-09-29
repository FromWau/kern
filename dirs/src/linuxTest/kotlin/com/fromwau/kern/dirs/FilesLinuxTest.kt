package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesLinuxTest {
    private val dir = newTempDir()
    private val links = mutableListOf<Path>()
    private val tooLarge = ByteArray(64 * 1024) { 'x'.code.toByte() }
    private val device = Path("/dev/null")

    @AfterTest
    fun cleanUp() {
        links.forEach { it.removeLink() }
        dir.deleteTree()
    }

    @Test
    fun `a write through a symlink replaces the target and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("old")
        val link = (dir / "link.toml")
            .symlinkTo(real)
            .also { links += it }

        assertEquals(Ok(Unit), link.writeText("new"))
        assertTrue(link.isSymlink())
        assertEquals("new", real.readRaw())
    }

    @Test
    fun `moveTo moves a symlink as itself and leaves its target where it is`() {
        val real = (dir / "real.toml").writeRaw("kept")
        val link = (dir / "link.toml").symlinkTo(real)
        val moved = (dir / "moved.toml").also { links += it }

        assertEquals(Ok(Unit), link.moveTo(moved))

        assertTrue(moved.isSymlink())
        assertEquals("kept", real.readRaw())
    }

    @Test
    fun `moveTo replaces an empty folder and refuses one with entries`() {
        val from = (dir / "from").also { SystemFileSystem.createDirectories(it) }
        val empty = (dir / "empty").also { SystemFileSystem.createDirectories(it) }
        val full = (dir / "full").also { SystemFileSystem.createDirectories(it) }
        (full / "entry").writeRaw("a")

        assertIs<FileError.WriteFailed>(from.moveTo(full).errorOrNull())
        assertEquals(Ok(Unit), from.moveTo(empty))
        assertFalse(from.exists())
    }

    @Test
    fun `writeText keeps the file itself with its permissions`() {
        val file = (dir / "secret.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)
        val inode = file.inode()

        assertEquals(Ok(Unit), file.writeText("new"))
        assertEquals("new", file.readRaw())
        assertEquals(inode, file.inode())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `writeText refuses a read-only file and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForWriting()) return

            assertIs<FileError.WriteFailed>(file.writeText("new").errorOrNull())
            assertEquals("old", file.readRaw())
            assertEquals(S_IRUSR, file.mode())
            assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `writeText refuses a file that may not be read and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IWUSR)
        try {
            // A runner that may read it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForReading()) return

            assertIs<FileError.WriteFailed>(file.writeText("new").errorOrNull())
            assertEquals(S_IWUSR, file.mode())
            assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
        assertEquals("old", file.readRaw())
    }

    @Test
    fun `a write into a folder that may not be written is refused and changes nothing`() {
        val folder = dir / "locked"
        SystemFileSystem.createDirectories(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            assertIs<FileError.WriteFailed>(file.writeText("new").errorOrNull())
            assertEquals("old", file.readRaw())
            assertEquals(listOf(file), SystemFileSystem.list(folder).toList())
        } finally {
            folder.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a writeText that fails midway puts the old content back`() {
        val file = (dir / "a.toml").writeRaw("old")

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge) }

        assertIs<FileError.WriteFailed>(result.errorOrNull())
        assertEquals("old", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a write of a new file that fails midway leaves nothing behind`() {
        val file = dir / "new.toml"

        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge) }

        assertIs<FileError.WriteFailed>(result.errorOrNull())
        assertEquals(emptyList<Path>(), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a failed first write through a dangling symlink keeps the link and removes the new file`() {
        val destination = dir / "dest.toml"
        val link = (dir / "link.toml")
            .symlinkTo(destination)
            .also { links += it }

        val result = withFileSizeLimit(bytes = 4096) { link.writeBytes(tooLarge) }

        assertIs<FileError.WriteFailed>(result.errorOrNull())
        assertTrue(link.isSymlink())
        assertFalse(destination.exists())
    }

    @Test
    fun `readText bounds the read even when the size check passed`() {
        // Files under /proc report a size of 0, like a file that grows after the check.
        val status = Path("/proc/self/status")

        assertEquals(
            Err(FileError.TooLarge(status, limitBytes = 10, atLeastBytes = 11)),
            status.readText(maxBytes = 10),
        )
    }

    @Test
    fun `createDirectories accepts a symlink to a directory and creates through it`() {
        val real = dir / "real"
        SystemFileSystem.createDirectories(real)
        val link = (dir / "link")
            .symlinkTo(real)
            .also { links += it }

        assertEquals(Ok(Unit), link.createDirectories())
        assertEquals(Ok(Unit), (link / "sub").createDirectories())
        assertTrue(SystemFileSystem.metadataOrNull(real / "sub")?.isDirectory == true)
    }

    @Test
    fun `readText refuses a FIFO without opening it`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Other)), fifo.readText())
    }

    @Test
    fun `writeText refuses a FIFO rather than blocking on a reader that may never come`() {
        // Opening a FIFO for writing waits for a reader, so writing one never returns: a caller above this
        // gets no result and, where it took a lock first, holds it for as long as the process lives.
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Other)), fifo.writeText("x = 1"))
    }

    @Test
    fun `readText refuses a device`() {
        assertEquals(Err(FileError.NotRegularFile(device, FileType.Other)), device.readText())
    }

    @Test
    fun `writeText refuses a device rather than writing into it`() {
        assertEquals(Err(FileError.NotRegularFile(device, FileType.Other)), device.writeText("x = 1"))
    }

    @Test
    fun `a FIFO as the walk root is NotRegularFile`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(listOf(Err(FileError.NotRegularFile(fifo, FileType.Other))), fifo.walkTopDown().toList())
    }

    @Test
    fun `a walk skips a FIFO and a dangling symlink`() {
        val file = (dir / "a.txt").writeRaw("a")
        (dir / "pipe").makeFifo()
        links += (dir / "dangling").symlinkTo(dir / "missing")

        assertEquals(listOf(Ok(file)), dir.walkTopDown().toList())
    }

    @Test
    fun `a directory that may not be read walks as Inaccessible`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        locked.withMode(0) {
            val error = dir
                .walkTopDown()
                .single()
                .errorOrNull()
            assertIs<FileError.Inaccessible>(error)
            assertEquals(locked, error.path)
        }
    }

    @Test
    fun `list of a directory that may not be read is Inaccessible`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        (locked / "app.toml").writeRaw("port = 1")
        // Searchable but not readable: the entries are there, and reading their names is what is refused.
        locked.withMode(S_IXUSR) {
            val error = locked.list().errorOrNull()
            assertIs<FileError.Inaccessible>(error)
            assertEquals(locked, error.path)
        }
    }

    @Test
    fun `deleting under a directory that may not be entered is Inaccessible rather than NotFound`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        locked.withMode(0) {
            assertIs<FileError.Inaccessible>(file.delete().errorOrNull())
        }
    }

    @Test
    fun `a symlink that loops is Inaccessible`() {
        val first = (dir / "first").also { links += it }
        val second = (dir / "second").also { links += it }
        first.symlinkTo(second)
        second.symlinkTo(first)

        assertIs<FileError.Inaccessible>(first.readText().errorOrNull())
    }

    @Test
    fun `a file under a directory that may not be entered is Inaccessible rather than NotFound`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        locked.withMode(0) {
            assertIs<FileError.Inaccessible>(file.readText().errorOrNull())
        }
    }

    @Test
    fun `a write whose restore also fails reports RestoreFailed and keeps the backup`() {
        val file = (dir / "a.toml").writeRaw("old")

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeBytes(tooLarge) }

        try {
            // A runner that may write the folder anyway, such as root, restores the backup and never gets here.
            if (dir.canCreateFileIn()) return

            val error = result.errorOrNull()
            assertIs<FileError.RestoreFailed>(error)
            val backup = assertNotNull(error.backup)
            assertEquals("old", backup.readRaw())
            // The file keeps what the failed write left in it, so the backup is the only intact copy.
            assertNotEquals("old", file.readRaw())
        } finally {
            dir.setMode(S_IRWXU)
        }
    }

    @Test
    fun `a RestoreFailed still names a backup that could not be looked up`() {
        val file = (dir / "a.toml").writeRaw("old")

        // Without the search bit the backup cannot be looked up at all, which is not the same as being gone.
        val result = withFileSizeLimitLocking(dir, bytes = 4096, mode = S_IRUSR) { file.writeBytes(tooLarge) }

        dir.withModeRestored {
            assertNotNull(assertIs<FileError.RestoreFailed>(result.errorOrNull()).backup)
        }
    }

    @Test
    fun `a backup carries the permissions of the file it copies`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)

        // Only a failed undo leaves the copy to look at: an ordinary write disposes of it before returning,
        // and that is the window a copy wider than the file would be readable in.
        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeBytes(tooLarge) }

        dir.withModeRestored {
            val backup = assertIs<FileError.RestoreFailed>(result.errorOrNull()).backup

            assertEquals(S_IRUSR or S_IWUSR, assertNotNull(backup).mode())
        }
    }

    @Test
    fun `a restore leaves the file the permissions it had`() {
        val file = (dir / "a.toml").writeRaw("old")
        file.setMode(S_IRUSR or S_IWUSR)

        // The restore moves the copy back over the file, so what the copy carries is what the file keeps.
        val result = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge) }

        assertIs<FileError.WriteFailed>(result.errorOrNull())
        assertEquals(S_IRUSR or S_IWUSR, file.mode())
        assertEquals("old", file.readRaw())
    }

    @Test
    fun `a RestoreFailed names no backup when the file was new`() {
        val file = dir / "new.toml"

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeBytes(tooLarge) }

        dir.withModeRestored {
            assertNull(assertIs<FileError.RestoreFailed>(result.errorOrNull()).backup)
        }
    }

    @Test
    fun `a RestoreFailed reports why the undo failed and not why the write did`() {
        val file = (dir / "a.toml").writeRaw("old")
        // The same write fails the same way in both runs. Only the second one cannot put the backup back, so
        // any difference in what is reported is the undo's doing.
        val failedWrite = withFileSizeLimit(bytes = 4096) { file.writeBytes(tooLarge) }
        val writeReason = assertIs<FileError.WriteFailed>(failedWrite.errorOrNull()).reason

        val result = withFileSizeLimitLocking(dir, bytes = 4096) { file.writeBytes(tooLarge) }

        try {
            // A runner that may write the folder anyway, such as root, restores the backup and never gets here.
            if (dir.canCreateFileIn()) return

            val error = assertIs<FileError.RestoreFailed>(result.errorOrNull())
            assertNotEquals(writeReason, error.reason)
        } finally {
            dir.setMode(S_IRWXU)
        }
    }

    @Test
    fun `leftoverBackups finds the backup beside the file a symlink points at`() {
        val real = (dir / "real.toml").writeRaw("old")
        val link = (dir / "link.toml")
            .symlinkTo(real)
            .also { links += it }
        (dir / ".real.toml.$A_WRITE_ID.bak").writeRaw("older")

        assertEquals(listOf(".real.toml.$A_WRITE_ID.bak"), link.leftoverBackups().getOrNull()?.map { it.name })
    }

    @Test
    fun `leftoverBackups finds the backup of a file named without a folder`() {
        val previous = currentDirectory()
        changeDirectory(dir)
        try {
            Path("rel.toml").writeRaw("a = 1")
            Path(".rel.toml.$A_WRITE_ID.bak").writeRaw("a = 0")

            val found = Path("rel.toml").leftoverBackups().getOrNull()

            assertEquals(listOf(".rel.toml.$A_WRITE_ID.bak"), found?.map { it.name })
        } finally {
            changeDirectory(previous)
        }
    }

    @Test
    fun `createDirectories below a folder that may not be searched is Inaccessible naming the path asked for`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        locked.withMode(0) {
            val asked = locked / "app" / "deeper"

            assertEquals(asked, assertIs<FileError.Inaccessible>(asked.createDirectories().errorOrNull()).path)
        }
    }

    @Test
    fun `a write under a folder that may not be searched is Inaccessible naming the folder`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        locked.withMode(0) {
            val result = (locked / "app" / "settings.toml").writeText("a = 1")

            assertEquals(locked / "app", assertIs<FileError.Inaccessible>(result.errorOrNull()).path)
        }
    }

    @Test
    fun `a write through a symlink whose target folder is missing fails on the link`() {
        val link = (dir / "link.toml")
            .symlinkTo(dir / "absent" / "app.toml")
            .also { links += it }

        val error = assertIs<FileError.WriteFailed>(link.writeText("a = 1").errorOrNull())

        assertEquals(link, error.path)
        assertFalse(SystemFileSystem.exists(dir / "absent"))
    }

    @Test
    fun `createDirectories takes a relative path from the working directory`() {
        val previous = currentDirectory()
        changeDirectory(dir)
        try {
            assertEquals(Ok(Unit), Path("nested", "deeper").createDirectories())
            assertTrue(SystemFileSystem.metadataOrNull(dir / "nested" / "deeper")?.isDirectory == true)
        } finally {
            changeDirectory(previous)
        }
    }

    @Test
    fun `deleteRecursively removes a symlink without following it`() {
        val outside = dir / "outside"
        SystemFileSystem.createDirectories(outside)
        val kept = (outside / "keep.txt").writeRaw("keep")
        val tree = dir / "tree"
        SystemFileSystem.createDirectories(tree)
        links += (tree / "link").symlinkTo(outside)

        assertEquals(Ok(Unit), tree.deleteRecursively())
        assertFalse(tree.exists())
        assertEquals("keep", kept.readRaw())
    }

    @Test
    fun `deleteRecursively reports the removal's own failure when the path is no directory`() {
        val folder = dir / "locked"
        SystemFileSystem.createDirectories(folder)
        val file = (folder / "a.toml").writeRaw("old")
        folder.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, removes the file and leaves nothing to observe.
            if (folder.canCreateFileIn()) return

            val error = file.deleteRecursively().errorOrNull()
            assertIs<FileError.WriteFailed>(error)
            assertEquals(file, error.path)
        } finally {
            folder.setMode(S_IRWXU)
        }
    }
}
