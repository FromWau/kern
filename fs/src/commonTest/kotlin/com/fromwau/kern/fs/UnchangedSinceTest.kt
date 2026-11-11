package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.io.files.Path

@OptIn(InternalKernApi::class)
class UnchangedSinceTest {
    private val dir = newTempDir()
    private val modes = listOf(WriteMode.Auto, WriteMode.Atomic, WriteMode.Replace, WriteMode.InPlace)

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a write given the stamp of the file as it is goes through in every mode`() {
        for (mode in modes) {
            val file = (dir / "ok-$mode.toml").writeRaw("old")
            val stamp = file.stamp().assertSuccess()

            file.writeText("new", mode, unchangedSince = stamp).assertSuccess()

            assertEquals("new", file.readRaw(), "$mode")
        }
    }

    @Test
    fun `a write given a stamp of a file written since is ChangedSince in every mode and writes nothing`() {
        for (mode in modes) {
            val file = (dir / "f-$mode.toml").writeRaw("old")
            val stamp = file.stamp().assertSuccess()
            file.writeRaw("theirs")

            assertEquals(Err(FileError.ChangedSince(file)), file.writeText("new", mode, unchangedSince = stamp))
            assertEquals("theirs", file.readRaw(), "$mode")
        }
        assertEquals(modes.map { "f-$it.toml" }.toSet(), dir.tree().keys)
    }

    @Test
    fun `a write given a stamp of a file of the same size given another modification time since is ChangedSince`() {
        val file = (dir / "f.toml").writeRaw("old")
        val stamp = file.stamp().assertSuccess()
        setLastModified(file, Instant.parse("2001-02-03T04:05:06Z"))

        file.writeBytes("new".encodeToByteArray(), unchangedSince = stamp).assertError<FileError.ChangedSince>()
        assertEquals("old", file.readRaw())
    }

    @Test
    fun `a write given a stamp of a file removed since is ChangedSince and makes no file`() {
        val file = (dir / "f.toml").writeRaw("old")
        val stamp = file.stamp().assertSuccess()
        file.delete().assertSuccess()

        file.writeText("new", unchangedSince = stamp).assertError<FileError.ChangedSince>()
        assertEquals(emptyMap(), dir.tree())
    }

    @Test
    fun `a stamped write of a file written since is ChangedSince rather than PendingBackup for a backup beside it`() {
        val file = (dir / "f.toml").writeRaw("old")
        val stamp = file.stamp().assertSuccess()
        val backup = (dir / ".f.toml.$A_WRITE_ID.bak").writeRaw("old")
        file.writeRaw("")

        file.writeText("new", unchangedSince = stamp).assertError<FileError.ChangedSince>()
        assertEquals(mapOf("f.toml" to "", backup.name to "old"), dir.tree())
    }

    @Test
    fun `a rename write refuses a file written after its temp was made and leaves no temp`() {
        val file = (dir / "f.toml").writeRaw("old")
        val stamp = file.stamp().assertSuccess()
        val writtenMeanwhile = { from: Path, _: Path ->
            from.writeRaw("theirs")
            true
        }

        val written = file.write(WriteMode.Replace, carryAttributes = writtenMeanwhile, unchangedSince = stamp) {
            it.write("new".encodeToByteArray())
        }

        assertEquals(Err(FileError.ChangedSince(file)), written)
        assertEquals(mapOf("f.toml" to "theirs"), dir.tree())
    }

    @Test
    fun `an in-place write refuses a file written after its backup was made and leaves no backup`() {
        val file = (dir / "f.toml").writeRaw("old")
        val stamp = file.stamp().assertSuccess()
        val writtenMeanwhile = { from: Path, to: Path, ownerOnly: Boolean ->
            PathFolder(from.parent).copyFile(from.name, to.name, ownerOnly).also { from.writeRaw("theirs") }
        }

        val written = file.write(WriteMode.InPlace, backUp = writtenMeanwhile, unchangedSince = stamp) {
            it.write("new".encodeToByteArray())
        }

        assertEquals(Err(FileError.ChangedSince(file)), written)
        assertEquals(mapOf("f.toml" to "theirs"), dir.tree())
    }
}
