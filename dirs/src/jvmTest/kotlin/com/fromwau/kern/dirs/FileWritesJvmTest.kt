package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `writeText refuses a read-only file and leaves it untouched`() {
        val file = (dir / "a.toml").writeRaw("old")
        val handle = File(file.toString())
        handle.setWritable(false)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe. Appending empties nothing.
            if (runCatching { FileOutputStream(handle, true).close() }.isSuccess) return

            assertIs<FileError.WriteFailed>(file.writeText("new").errorOrNull())
            assertEquals("old", file.readRaw())
            assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
        } finally {
            handle.setWritable(true)
        }
    }
}
