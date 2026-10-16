package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.io.files.SystemFileSystem

class AppendingTest {
    private val dir = newTempDir()
    private val file = dir / "app.log"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `each append lands after what the file already held`() {
        file.writeRaw("first\n")
        val opened = file.openAppend().assertSuccess()

        val second = opened.append("second\n".encodeToByteArray())
        val third = opened.append("third\n".encodeToByteArray())
        opened.close()

        assertEquals(listOf(Ok(Unit), Ok(Unit)), listOf(second, third))
        assertEquals("first\nsecond\nthird\n", file.readRaw())
    }

    @Test
    fun `a missing file and its missing parent folders are created`() {
        val nested = dir / "a" / "b" / "app.log"

        val opened = nested.openAppend().assertSuccess()
        opened.close()

        assertTrue(SystemFileSystem.exists(nested))
        assertEquals(nested, opened.path)
    }

    @Test
    fun `a directory at the path is NotRegularFile`() {
        SystemFileSystem.createDirectories(file)

        val error = file.openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.Directory, error.type)
    }

    @Test
    fun `a file standing in place of a parent folder is NotADirectory`() {
        val blocker = (dir / "blocker").writeRaw("")

        (blocker / "app.log").openAppend().assertError<FileError.NotADirectory>()
    }

    @Test
    fun `the last byte is read from the open file and is null while it is empty`() {
        val opened = file.openAppend().assertSuccess()
        val empty = opened.lastByte()

        val _ = opened.append("{\"torn".encodeToByteArray())
        val torn = opened.lastByte()
        opened.close()

        assertEquals(Ok(null), empty)
        assertEquals(Ok('n'.code.toByte()), torn)
    }

    @Test
    fun `an append after close fails and a second close does nothing`() {
        val opened = file.openAppend().assertSuccess()
        opened.close()
        opened.close()

        opened.append("late\n".encodeToByteArray()).assertError<FileError.WriteFailed>()
        assertEquals("", file.readRaw())
    }
}
