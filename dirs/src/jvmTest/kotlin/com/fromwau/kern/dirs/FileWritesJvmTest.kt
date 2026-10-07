package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.errorOrNull
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileWritesJvmTest {
    private val dir = newTempDir()
    private val link = dir / "link.toml"

    @AfterTest
    fun cleanUp() {
        Files.deleteIfExists(Paths.get(link.toString()))
        dir.deleteTree()
    }

    @Test
    fun `a write through a symlink replaces the target and keeps the link`() {
        val real = (dir / "real.toml").writeRaw("old")
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get(real.toString()))

        assertEquals(Ok(Unit), link.writeText("new"))
        assertTrue(Files.isSymbolicLink(Paths.get(link.toString())))
        assertEquals("new", real.readRaw())
    }

    @Test
    fun `deleting under a directory that may not be entered is Inaccessible rather than NotFound`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        val file = (locked / "a.toml").writeRaw("old")
        shutOut(locked)
        try {
            // A runner that may look anyway, such as root, leaves nothing to observe.
            if (canLookUp(file)) return

            file.delete().assertError<FileError.Inaccessible>()
        } finally {
            restore(locked)
        }
    }

    @Test
    fun `createDirectories below a folder that may not be searched is Inaccessible naming the path asked for`() {
        val locked = dir / "locked"
        SystemFileSystem.createDirectories(locked)
        shutOut(locked)
        try {
            // A runner that may look anyway, such as root, leaves nothing to observe.
            if (canLookUp(locked / "app")) return

            val asked = locked / "app" / "deeper"

            assertEquals(asked, asked.createDirectories().assertError<FileError.Inaccessible>().path)
        } finally {
            restore(locked)
        }
    }

    @Test
    fun `createDirectories in a folder that may not be written says why`() {
        val readOnly = dir / "readonly"
        SystemFileSystem.createDirectories(readOnly)
        File(readOnly.toString()).setWritable(false)
        try {
            // A runner that may write anyway, such as root, leaves nothing to observe.
            if (File(readOnly.toString(), "probe").mkdir()) return

            val failure = (readOnly / "app").createDirectories().assertError<FileError.WriteFailed>()

            assertEquals("Permission denied", failure.reason)
        } finally {
            File(readOnly.toString()).setWritable(true)
        }
    }

    @Test
    fun `a symlink loop the JDK reports by its exception alone reads in the OS's words`() {
        // JDK 27 throws this with no reason at all, so its class name was all a caller saw.
        val probe = FileSystemLoopException("/a/loop").asProbe()

        assertEquals(PathProbe.Denied("Too many levels of symbolic links"), probe)
    }

    @Test
    fun `a walk through a symlink loop says so in the OS's words`() {
        val notebook = dir / "linky"
        SystemFileSystem.createDirectories(notebook)
        val loop = Paths.get((notebook / "loop").toString())
        Files.createSymbolicLink(loop, Paths.get("../linky"))
        try {
            val reasons = notebook.walkTopDown()
                .mapNotNull { (it.errorOrNull() as? FileError.Inaccessible)?.reason }
                .toSet()

            assertTrue(reasons.isNotEmpty(), "the loop was never reported")
            assertTrue(reasons.none { it.endsWith("Exception") }, "a reason is a class name: $reasons")
        } finally {
            // The cleanup follows links, so a loop left in place would walk it forever.
            Files.delete(loop)
        }
    }

    @Test
    fun `writeText refuses a read-only file and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        val handle = File(file.toString())
        handle.setWritable(false)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe. Appending empties nothing.
            if (runCatching { FileOutputStream(handle, true).close() }.isSuccess) return

            file.writeText("new").assertError<FileError.WriteFailed>()
            assertEquals("old", file.readRaw())
            assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
        } finally {
            handle.setWritable(true)
        }
    }

    @Test
    fun `the copy a write makes takes the file's own permissions`() {
        val file = (dir / "a.toml").writeRaw("secret")
        val copy = (dir / "b.toml").writeRaw("anything")
        val owned = PosixFilePermissions.fromString("rw-------")
        Files.setPosixFilePermissions(Paths.get(file.toString()), owned)

        // Called directly because this is where a backup's permissions are decided, and because it is the
        // JVM's own implementation, which no test on a native target reaches.
        copyPermissions(from = file, to = copy)

        assertEquals(owned, Files.getPosixFilePermissions(Paths.get(copy.toString())))
    }
}
