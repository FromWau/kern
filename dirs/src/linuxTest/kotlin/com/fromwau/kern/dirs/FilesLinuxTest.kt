package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ObsoleteWorkersApi::class)
class FilesLinuxTest {
    private val dir = newTempDir()
    private val links = mutableListOf<Path>()
    private val tooLarge = ByteArray(64 * 1024) { 'x'.code.toByte() }

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
    fun `createDirectories below a folder that may not be searched names the first missing level`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        locked.setMode(0)
        try {
            // A runner that may list it anyway, such as root, leaves nothing to observe.
            if (locked.canList()) return

            val error = (locked / "a" / "b").createDirectories().errorOrNull()
            assertIs<FileError.WriteFailed>(error)
            assertEquals(locked / "a", error.path)
        } finally {
            locked.setMode(S_IRWXU)
        }
    }

    @Test
    fun `readText refuses a FIFO without opening it`() {
        val fifo = (dir / "pipe").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Other)), fifo.readText())
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
        locked.setMode(0)
        try {
            // A runner that may list it anyway, such as root, leaves nothing to observe.
            if (locked.canList()) return

            val error = dir
                .walkTopDown()
                .single()
                .errorOrNull()
            assertIs<FileError.Inaccessible>(error)
            assertEquals(locked, error.path)
        } finally {
            locked.setMode(S_IRWXU)
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
    fun `writeText into a FIFO reaches its reader`() {
        val fifo = (dir / "pipe").makeFifo()
        val reader = Worker.start()
        val read = reader.execute(TransferMode.SAFE, { fifo.toString() }, ::readFully)

        assertEquals(Ok(Unit), fifo.writeText("through the pipe"))
        assertEquals("through the pipe", read.result)

        reader.requestTermination().result
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
