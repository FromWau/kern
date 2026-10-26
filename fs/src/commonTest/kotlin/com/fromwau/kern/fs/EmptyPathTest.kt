package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlinx.io.files.Path

class EmptyPathTest {
    private val dir = newTempDir()
    private val empty = Path("")
    private val notFound = Err(FileError.NotFound(empty))

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `an empty path does not exist`() {
        assertFalse(empty.exists())
    }

    @Test
    fun `fileType list and walkTopDown of an empty path are NotFound`() {
        assertEquals(notFound, empty.fileType())
        assertEquals(notFound, empty.list())
        assertEquals(notFound, empty.walkTopDown())
    }

    @Test
    fun `readText and readBytes of an empty path are NotFound`() {
        assertEquals(notFound, empty.readText())
        assertEquals(notFound, empty.readBytes())
    }

    @Test
    fun `fileSize and modifiedAt of an empty path are NotFound`() {
        assertEquals(notFound, empty.fileSize())
        assertEquals(notFound, empty.modifiedAt())
    }

    @Test
    fun `isSymlink and realPath of an empty path are NotFound`() {
        assertEquals(notFound, empty.isSymlink())
        assertEquals(notFound, empty.realPath())
    }

    @Test
    fun `delete of an empty path is NotFound`() {
        assertEquals(notFound, empty.delete())
    }

    @Test
    fun `moveTo from an empty path is NotFound and creates nothing`() {
        val target = dir / "new" / "b.txt"

        assertEquals(notFound, empty.moveTo(target))
        assertFalse((dir / "new").exists())
    }

    @Test
    fun `writeText to an empty path is NotFound`() {
        assertEquals(notFound, empty.writeText("x"))
    }

    @Test
    fun `writeText in place to an empty path is NotFound`() {
        assertEquals(notFound, empty.writeText("x", WriteMode.InPlace))
    }

    @Test
    fun `writeBytes to an empty path is NotFound`() {
        assertEquals(notFound, empty.writeBytes(byteArrayOf(1), WriteMode.Atomic))
    }

    @Test
    fun `copyTo an empty path is NotFound`() {
        val source = (dir / "a.txt").writeRaw("a")

        assertEquals(notFound, source.copyTo(empty))
    }

    @Test
    fun `openAppend of an empty path is NotFound`() {
        assertEquals(notFound, empty.openAppend())
    }

    @Test
    fun `leftovers of an empty path is NotFound`() {
        assertEquals(notFound, empty.leftovers())
    }

    @Test
    fun `createDirectories of an empty path is NotFound`() {
        assertEquals(notFound, empty.createDirectories())
    }

    @Test
    fun `deleteRecursively of an empty path is NotFound`() {
        assertEquals(notFound, empty.deleteRecursively())
    }

    @Test
    fun `moveTo an empty path is NotFound and leaves the source`() {
        val source = (dir / "a.txt").writeRaw("a")

        assertEquals(notFound, source.moveTo(empty))
        assertEquals("a", source.readRaw())
    }

    @Test
    fun `withLock on an empty path is NotFound and runs nothing`() {
        var ran = false

        assertEquals(notFound, empty.withLock(wait = Duration.ZERO) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `tryLock on an empty path is NotFound`() {
        assertEquals(notFound, empty.tryLock())
    }
}
