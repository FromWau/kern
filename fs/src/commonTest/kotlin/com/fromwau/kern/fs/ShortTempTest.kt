package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class ShortTempTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun named(bytes: Int): Path = dir / "a".repeat(bytes)

    private fun entries(): Set<Path> = SystemFileSystem.list(dir).toSet()

    @Test
    fun `an atomic or replacing write to a name of up to 255 bytes renames and leaves nothing beside it`() {
        for (bytes in listOf(214, 255)) {
            val file = named(bytes).writeRaw("old")

            assertEquals(Ok(WriteWay.Renamed), file.writeText("new", mode = WriteMode.Atomic).way())
            assertEquals(Ok(WriteWay.Renamed), named(bytes - 1).writeText("new", mode = WriteMode.Replace).way())
            assertEquals("new", file.readRaw())
            assertEquals(setOf(file, named(bytes - 1)), entries())
            SystemFileSystem.delete(file)
            SystemFileSystem.delete(named(bytes - 1))
        }
    }

    @Test
    fun `a copy onto a name of 255 bytes keeps the attributes it is asked to`() {
        val source = (dir / "source.txt").writeRaw("content")
        val longAgo = Instant.fromEpochSeconds(1_000_000_000)
        openFileSink(source, append = true).use { it.setModifiedTime(longAgo) }
        setFilePermissions(source, 0b111_101_000)
        val target = named(255)

        assertEquals(Ok(WriteWay.Renamed), source.copyTo(target, keepAttributes = true).way())
        assertEquals("content", target.readRaw())
        assertEquals(Ok(longAgo), target.modifiedAt())
        target.assertPermissions(0b111_101_000)
    }

    @Test
    fun `a writer write with replaceLink onto a link of 255 bytes replaces the link and leaves its target`() {
        val real = (dir / "real.txt").writeRaw("old")
        val link = links.make(named(255), real)

        assertEquals(
            Ok(WriteWay.Renamed),
            dir.writer().assertSuccess().writeText(link.name, "new", replaceLink = true).way(),
        )
        assertEquals(Ok(false), link.isSymlink())
        assertEquals("new", link.readRaw())
        assertEquals("old", real.readRaw())
    }

    @Test
    fun `a temp without a file name a crashed write left is found as UnnamedTemp and discarded`() {
        val temp = (dir / ".$A_WRITE_ID.tmp").writeRaw("partial")

        val scan = dir.leftoversIn().assertSuccess()

        assertEquals(listOf<Leftover>(Leftover.UnnamedTemp(temp, A_WRITE_START)), scan.found)
        assertEquals(Ok(Unit), dir.writer().assertSuccess().discard(scan.found.single()))
        assertEquals(emptySet(), entries())
    }

    @Test
    fun `the leftovers of one file leave out a temp without a file name`() {
        val file = named(255).writeRaw("old")
        (dir / ".$A_WRITE_ID.tmp").writeRaw("partial")

        assertEquals(Ok(emptyList()), file.leftovers())
    }

    @Test
    fun `an in-place write of a name of 214 bytes is still NameTooLong for its backup`() {
        val file = named(214).writeRaw("old")

        val written = file.writeText("new", mode = WriteMode.InPlace)

        assertEquals(Err(FileError.NameTooLong(file, LongName.PartialBackup, bytes = 214 + 47)), written)
        assertEquals(setOf(file), entries())
    }
}
