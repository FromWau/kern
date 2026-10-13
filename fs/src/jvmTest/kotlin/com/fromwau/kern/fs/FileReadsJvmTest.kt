package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

        val error = file.readText().assertError<FileError.Inaccessible>()
        assertEquals(file, error.path)
        assertTrue(error.reason.isNotBlank())
    }

    @Test
    fun `a directory under one that may not be entered is Inaccessible rather than NotFound`() {
        val inner = locked / "inner"
        SystemFileSystem.createDirectories(inner)
        shutOut(locked)

        if (canLookUp(inner)) return

        inner.list().assertError<FileError.Inaccessible>()
    }

    @Test
    fun `a directory that may not be read is Inaccessible rather than empty`() {
        SystemFileSystem.createDirectories(locked)
        (locked / "app.toml").writeRaw("port = 1")
        File(locked.toString()).setReadable(false)

        // A runner that may read it anyway, such as root, leaves nothing to observe.
        if (canList(locked)) return

        val error = locked.list().assertError<FileError.Inaccessible>()
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

        unparsable.readText().assertError<FileError.NotFound>()
    }

    @Test
    fun `a symlink that loops is Inaccessible`() {
        val first = dir / "first"
        val second = dir / "second"
        links += listOf(first.toString(), second.toString())
        Files.createSymbolicLink(Paths.get(first.toString()), Paths.get(second.toString()))
        Files.createSymbolicLink(Paths.get(second.toString()), Paths.get(first.toString()))

        first.readText().assertError<FileError.Inaccessible>()
    }

    @Test
    fun `modifiedAt reads the time to the nanosecond the file system keeps`() {
        val file = (dir / "stamped.txt").writeRaw("x")
        val stamp = java.time.Instant.parse("2024-02-29T12:34:56.123456789Z")
        Files.setLastModifiedTime(Paths.get(file.toString()), FileTime.from(stamp))
        val kept = Files.getLastModifiedTime(Paths.get(file.toString())).toInstant()

        val read = file.modifiedAt().assertSuccess()

        assertEquals(Instant.fromEpochSeconds(kept.epochSecond, kept.nano), read)
        // Only a file system that kept every digit can show none of them is dropped on the way.
        if (kept == stamp) assertEquals(Instant.fromEpochSeconds(1709210096, 123456789), read)
    }
}
