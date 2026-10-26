package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant
import kotlinx.io.files.Path

/** The [FileInfo] each write answers, which describes the file it left as a later [info] does. */
@OptIn(InternalKernApi::class)
class WrittenInfoTest {
    private val dir = newTempDir()
    private val writer = dir.writer().assertSuccess()
    private val source = (dir / "source.txt").writeRaw("copied!")
    private val time = Instant.fromEpochSeconds(1_700_000_000)

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    /** Checks [written] was written [way] and holds [size] bytes, and that its info is the one [now] reads later. */
    private fun assertDescribes(
        now: () -> Result<FileInfo, FileError>,
        size: Long,
        way: WriteWay,
        written: Result<Copied, FileError>,
    ) {
        val answered = assertIs<Written>(written.assertSuccess())
        assertEquals(way, answered.way)
        assertEquals(size, answered.info.size)
        assertEquals(FileType.Regular, answered.info.type)
        assertEquals(now().assertSuccess(), answered.info)
    }

    private fun assertDescribes(
        file: Path,
        size: Long,
        way: WriteWay,
        written: Result<Copied, FileError>,
    ) = assertDescribes({ file.info() }, size, way, written)

    private fun assertDescribes(
        name: String,
        size: Long,
        way: WriteWay,
        written: Result<Copied, FileError>,
    ) = assertDescribes({ writer.info(name) }, size, way, written)

    @Test
    fun `a path write by rename answers the info of the new file`() {
        val file = dir / "a.txt"

        assertDescribes(file, 3, WriteWay.Renamed, file.writeText("new"))
        assertDescribes(file, 4, WriteWay.Renamed, file.writeBytes("four".encodeToByteArray(), WriteMode.Replace))
    }

    @Test
    fun `a path write in place answers the info of the file it wrote`() {
        val file = (dir / "a.txt").writeRaw("old content")

        assertDescribes(file, 3, WriteWay.InPlace, file.writeText("new", WriteMode.InPlace))
    }

    @Test
    fun `a path write given modifiedAt answers that time`() {
        val file = (dir / "a.txt").writeRaw("old")

        val renamed = file.writeText("new", modifiedAt = time)
        assertDescribes(file, 3, WriteWay.Renamed, renamed)
        assertEquals(time, assertIs<Written>(renamed.assertSuccess()).info.modifiedAt)
        val inPlace = file.writeText("newer", WriteMode.InPlace, modifiedAt = time)
        assertDescribes(file, 5, WriteWay.InPlace, inPlace)
        assertEquals(time, assertIs<Written>(inPlace.assertSuccess()).info.modifiedAt)
    }

    @Test
    fun `a path copy answers the info of the copy`() {
        val target = dir / "copy.txt"

        assertDescribes(target, 7, WriteWay.Renamed, source.copyTo(target))
        assertDescribes(target, 7, WriteWay.InPlace, source.copyTo(target, WriteMode.InPlace, modifiedAt = time))
        assertEquals(time, target.info().assertSuccess().modifiedAt)
    }

    @Test
    fun `a path copy onto itself answers Unchanged with the file's info`() {
        val unchanged = assertIs<Copied.Unchanged>(source.copyTo(source).assertSuccess())

        assertEquals(source.info().assertSuccess(), unchanged.info)
    }

    @Test
    fun `a writer write answers the info of the file it left`() {
        assertDescribes("w.txt", 3, WriteWay.Renamed, writer.writeText("w.txt", "new"))
        assertDescribes("w.txt", 5, WriteWay.InPlace, writer.writeText("w.txt", "newer", inPlace = true))
        val bytes = "bytes".encodeToByteArray()
        assertDescribes("b.bin", 5, WriteWay.Renamed, writer.writeBytes("b.bin", bytes, modifiedAt = time))
        assertEquals(time, (dir / "b.bin").info().assertSuccess().modifiedAt)
    }

    @Test
    fun `a writer copy answers the info of the copy`() {
        assertDescribes("c.txt", 7, WriteWay.Renamed, writer.copyFrom(source, "c.txt"))
        assertDescribes("k.txt", 7, WriteWay.Renamed, writer.copyFrom(source, "k.txt", keepAttributes = true))
        assertEquals(source.info().assertSuccess().modifiedAt, (dir / "k.txt").info().assertSuccess().modifiedAt)
    }

    @Test
    fun `a copy answers its own info and not its source's`() {
        setLastModified(source, time)
        val sourceInfo = source.info().assertSuccess()

        val copiedTo = assertIs<Written>(source.copyTo(dir / "to.txt").assertSuccess())
        val copiedFrom = assertIs<Written>(writer.copyFrom(source, "from.txt").assertSuccess())

        assertNotEquals(sourceInfo, copiedTo.info)
        assertEquals((dir / "to.txt").info().assertSuccess(), copiedTo.info)
        assertNotEquals(sourceInfo, copiedFrom.info)
        assertEquals(writer.info("from.txt").assertSuccess(), copiedFrom.info)
    }

    @Test
    fun `a private writer write answers permissions 600`() {
        createDirectoriesRaw(dir / "private", OWNER_ONLY_FOLDER)
        val private = (dir / "private").privateWriter().assertSuccess()

        val written = assertIs<Written>(private.writeText("token", "t").assertSuccess())

        assertEquals(private.info("token").assertSuccess(), written.info)
        if (dir.permissions().assertSuccess() == null) return
        assertEquals(Permissions(0b110_000_000), written.info.permissions)
    }
}
