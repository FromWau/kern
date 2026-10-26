package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.setModifiedAt
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.io.files.Path

class CopyModifiedAtTest {
    private val dir = newTempDir()
    private val source = (dir / "source.md").writeRaw("text")
    private val writer = (dir / "out").also { createDirectoriesRaw(it) }.writer().assertSuccess()
    private val sourceTime = Instant.fromEpochSeconds(1_600_000_000)
    private val given = Instant.fromEpochSeconds(1_700_000_000)

    init {
        source.setModifiedAt(sourceTime).assertSuccess()
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun modifiedAt(path: Path): Instant = path.info().assertSuccess().modifiedAt

    @Test
    fun `copyFrom with modifiedAt gives the copy that time`() {
        writer.copyFrom(source, "a.md", modifiedAt = given).assertSuccess()

        assertEquals(given, modifiedAt(writer.root / "a.md"))
        assertEquals("text", (writer.root / "a.md").readRaw())
    }

    @Test
    fun `copyFrom with modifiedAt and keepAttributes gives the copy the time asked for over the source's`() {
        writer.copyFrom(source, "a.md", keepAttributes = true, modifiedAt = given).assertSuccess()

        assertEquals(given, modifiedAt(writer.root / "a.md"))
    }

    @Test
    fun `copyFrom with keepAttributes and no modifiedAt gives the copy the source's time`() {
        writer.copyFrom(source, "a.md", keepAttributes = true).assertSuccess()

        assertEquals(sourceTime, modifiedAt(writer.root / "a.md"))
    }

    @Test
    fun `copyTo with modifiedAt gives the copy that time`() {
        val target = dir / "b.md"

        source.copyTo(target, modifiedAt = given).assertSuccess()

        assertEquals(given, modifiedAt(target))
        assertEquals("text", target.readRaw())
    }

    @Test
    fun `copyTo with modifiedAt and keepAttributes gives the copy the time asked for over the source's`() {
        val target = dir / "b.md"

        source.copyTo(target, keepAttributes = true, modifiedAt = given).assertSuccess()

        assertEquals(given, modifiedAt(target))
    }

    @Test
    fun `copyTo in place with modifiedAt and keepAttributes gives the existing file the time asked for`() {
        val target = (dir / "b.md").writeRaw("old")

        source.copyTo(target, WriteMode.InPlace, keepAttributes = true, modifiedAt = given).assertSuccess()

        assertEquals(given, modifiedAt(target))
        assertEquals("text", target.readRaw())
    }
}
