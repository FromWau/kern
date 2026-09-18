package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileReadsJvmTest {
    private val dir = newTempDir()
    private val locked = dir / "locked"
    private val links = mutableListOf<String>()

    @AfterTest
    fun cleanUp() {
        restore(locked)
        links.forEach { Files.deleteIfExists(Paths.get(it)) }
        dir.deleteTree()
    }

    @Test
    fun `a file under a directory that may not be entered is Inaccessible rather than NotFound`() {
        SystemFileSystem.createDirectories(locked)
        val file = (locked / "app.toml").writeRaw("port = 1")
        shutOut(locked)

        // A runner that may look anyway, such as root, leaves nothing to observe.
        if (canLookUp(file)) return

        val error = file.readText().errorOrNull()
        assertIs<FileError.Inaccessible>(error)
        assertEquals(file, error.path)
        assertTrue(error.reason.isNotBlank())
    }

    @Test
    fun `a directory under one that may not be entered is Inaccessible rather than NotFound`() {
        val inner = locked / "inner"
        SystemFileSystem.createDirectories(inner)
        shutOut(locked)

        if (canLookUp(inner)) return

        assertIs<FileError.Inaccessible>(inner.list().errorOrNull())
    }

    @Test
    fun `a directory that may not be read is Inaccessible rather than empty`() {
        SystemFileSystem.createDirectories(locked)
        (locked / "app.toml").writeRaw("port = 1")
        File(locked.toString()).setReadable(false)

        // A runner that may read it anyway, such as root, leaves nothing to observe.
        if (canList(locked)) return

        val error = locked.list().errorOrNull()
        assertIs<FileError.Inaccessible>(error)
        assertEquals(locked, error.path)
    }

    @Test
    fun `a walk reports a directory it may not read instead of passing over it`() {
        SystemFileSystem.createDirectories(locked)
        (locked / "hidden.toml").writeRaw("port = 1")
        val top = (dir / "top.toml").writeRaw("x")
        // Readable off, executable left on, so the walk still sees a directory worth descending into.
        File(locked.toString()).setReadable(false)

        if (canList(locked)) return

        val walked = dir.walkTopDown().toList()
        assertEquals(listOf(Ok(top)), walked.filterIsInstance<Result.Success<Path>>())
        assertTrue(walked.any { (it.errorOrNull() as? FileError.Inaccessible)?.path == locked })
    }

    @Test
    fun `an empty directory lists as empty`() {
        val empty = dir / "empty"
        SystemFileSystem.createDirectories(empty)

        assertEquals(Ok(emptyList()), empty.list())
    }

    @Test
    fun `a name the platform cannot parse is an error rather than a throw`() {
        // A NUL byte is the one name java.nio refuses to parse at all, where java.io only answered false.
        val unparsable = dir / ("a" + Char(0) + "b")

        assertIs<FileError.NotFound>(unparsable.readText().errorOrNull())
    }

    @Test
    fun `a symlink that loops is Inaccessible`() {
        val first = dir / "first"
        val second = dir / "second"
        links += listOf(first.toString(), second.toString())
        Files.createSymbolicLink(Paths.get(first.toString()), Paths.get(second.toString()))
        Files.createSymbolicLink(Paths.get(second.toString()), Paths.get(first.toString()))

        assertIs<FileError.Inaccessible>(first.readText().errorOrNull())
    }
}
