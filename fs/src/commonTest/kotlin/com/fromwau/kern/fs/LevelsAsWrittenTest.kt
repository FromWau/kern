package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LevelsAsWrittenTest {
    private val dir = newTempDir()
    private val stage = (dir / "stage").also { createDirectoriesRaw(it) }
    private val bytes = byteArrayOf(1, 2, 3)

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun Path.isFolder() = fileType() == Ok(FileType.Directory)

    @Test
    fun `a write through a missing folder and back up makes that folder and lands beside it`() {
        val target = stage / "a" / ".." / "inside"

        assertEquals(Ok(WriteWay.Renamed), target.writeBytes(bytes).way())
        assertEquals(true, (stage / "a").isFolder())
        assertEquals(listOf(stage / "a", stage / "inside"), SystemFileSystem.list(stage).sortedBy { it.name })
    }

    @Test
    fun `a write through an existing folder and back up lands beside it`() {
        createDirectoriesRaw(stage / "a")
        val target = stage / "a" / ".." / "inside"

        assertEquals(Ok(WriteWay.Renamed), target.writeBytes(bytes).way())
        assertEquals(Ok(3L), (stage / "inside").fileSize())
    }

    @Test
    fun `writeText and copyTo and an InPlace write make the missing folders they pass through`() {
        val source = (dir / "source.txt").writeRaw("x")

        assertEquals(Ok(WriteWay.Renamed), (stage / "t" / ".." / "text.txt").writeText("x").way())
        assertEquals(Ok(WriteWay.Renamed), source.copyTo(stage / "c" / "d" / ".." / "copy.txt").way())
        assertEquals(
            Ok(WriteWay.InPlace),
            (stage / "p" / ".." / "p.txt").writeText("x", mode = WriteMode.InPlace).way(),
        )
        assertEquals(true, (stage / "t").isFolder())
        assertEquals(true, (stage / "c" / "d").isFolder())
        assertEquals(true, (stage / "p").isFolder())
        assertEquals("x", (stage / "c" / "copy.txt").readRaw())
    }

    @Test
    fun `createDirectories of a path ending in dot dot makes every level it passes through`() {
        assertEquals(Ok(Unit), (stage / "a" / "ok" / "..").createDirectories())

        assertEquals(true, (stage / "a" / "ok").isFolder())
    }

    @Test
    fun `createDirectories makes every named level of a path with dot and dot dot in the middle`() {
        assertEquals(Ok(Unit), (stage / "b" / "c" / "." / ".." / "d" / "..").createDirectories())

        val made = SystemFileSystem.list(stage / "b").sortedBy { it.name }
        assertEquals(listOf(stage / "b" / "c", stage / "b" / "d"), made)
    }

    @Test
    fun `moveTo makes the missing folders its target passes through`() {
        val source = (stage / "a.txt").writeRaw("a")

        assertEquals(Ok(Unit), source.moveTo(stage / "new" / ".." / "moved.txt"))
        assertEquals(true, (stage / "new").isFolder())
        assertEquals("a", (stage / "moved.txt").readRaw())
    }
}
