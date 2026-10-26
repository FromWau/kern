package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class AppendingTest {
    private val dir = newTempDir()
    private val file = dir / "app.log"
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
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
    fun `appendLine adds the text and a line break after what the file held`() {
        file.writeRaw("first\n")

        val appended = file
            .openAppend()
            .assertSuccess()
            .use { listOf(it.appendLine("second"), it.appendLine("")) }

        assertEquals(listOf(Ok(Unit), Ok(Unit)), appended)
        assertEquals("first\nsecond\n\n", file.readRaw())
    }

    @Test
    fun `appendLine writes a line break inside the text as it is`() {
        val appended = file
            .openAppend()
            .assertSuccess()
            .use { it.appendLine("a\nb") }

        assertEquals(Ok(Unit), appended)
        assertEquals("a\nb\n", file.readRaw())
    }

    // Real threads, so the two files' appends run at once rather than taking turns.
    @Test
    fun `lines appended by two writers at once never mix inside a line`() = runTest {
        val lines = listOf("a".repeat(200), "b".repeat(200))
        val writers = lines.map { file.openAppend().assertSuccess() }

        withContext(Dispatchers.Default) {
            for ((writer, line) in writers.zip(lines)) {
                launch { repeat(2_000) { writer.appendLine(line).assertSuccess() } }
            }
        }
        writers.forEach { it.close() }

        val written = file.readRaw().split("\n")
        assertEquals("", written.last())
        assertEquals(4_000, written.size - 1)
        assertEquals(emptyList(), written.dropLast(1).filter { it !in lines })
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
        createDirectoriesRaw(file)

        val error = file.openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.Directory, error.type)
    }

    @Test
    fun `a path ending in dot dot names a folder and makes nothing`() {
        val logs = dir / "logs"
        createDirectoriesRaw(logs)
        val path = Path(logs, "new", "..")

        assertEquals(Err(FileError.NotRegularFile(path, FileType.Directory)), path.openAppend())
        assertFalse(SystemFileSystem.exists(logs / "new"))
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
    fun `an append after close is Closed and a second close does nothing`() {
        val opened = file.openAppend().assertSuccess()
        opened.close()
        opened.close()

        assertEquals(Err(FileError.Closed(file)), opened.append("late\n".encodeToByteArray()))
        assertEquals("", file.readRaw())
    }

    @Test
    fun `the last byte of a closed AppendFile is Closed`() {
        val opened = file.openAppend().assertSuccess()
        opened.close()

        assertEquals(Err(FileError.Closed(file)), opened.lastByte())
    }

    @Test
    fun `a closed AppendFile is Closed`() {
        val opened = file.openAppend().assertSuccess()
        opened.close()

        assertEquals(Ok(AppendState.Closed), opened.state())
    }

    @Test
    fun `an open file still at its path is Same`() {
        val opened = file.openAppend().assertSuccess()

        val state = opened.state()
        opened.close()

        assertEquals(Ok(AppendState.Same), state)
    }

    @Test
    fun `ownerOnly creates a log only its owner may read and write`() {
        file.openAppend(ownerOnly = true).assertSuccess().use { it.appendLine("a").assertSuccess() }

        file.assertPermissions(0b110_000_000)
        assertEquals("a\n", file.readRaw())
    }

    @Test
    fun `ownerOnly narrows a log others may read before the first append`() {
        file.writeRaw("old\n")
        file.chmod(0b110_100_100).assertSuccess()

        file.openAppend(ownerOnly = true).assertSuccess().use {
            file.assertPermissions(0b110_000_000)
            it.appendLine("new").assertSuccess()
        }

        assertEquals("old\nnew\n", file.readRaw())
    }

    @Test
    fun `a reopen with ownerOnly creates the new log only its owner may read and write`() {
        file.openAppend(Reopen.WhenMoved(), ownerOnly = true).assertSuccess().use {
            it.appendLine("before").assertSuccess()
            SystemFileSystem.atomicMove(file, dir / "app.log.1")
            it.appendLine("after").assertSuccess()
        }

        file.assertPermissions(0b110_000_000)
        assertEquals("after\n", file.readRaw())
    }

    @Test
    fun `a writer reopen with replaceLink replaces a link put at the name and leaves what it led to alone`() {
        val inside = (dir / "inside").also { createDirectoriesRaw(it) }
        val outside = (dir / "outside.log").writeRaw("")
        val log = inside / "app.log"

        val writer = inside.writer().assertSuccess()
        writer.openAppend("app.log", Reopen.WhenMoved(), replaceLink = true).assertSuccess().use {
            it.appendLine("before").assertSuccess()
            SystemFileSystem.atomicMove(log, inside / "app.log.1")
            links.make(log, outside)

            it.appendLine("after").assertSuccess()
        }
        assertEquals(Ok(false), log.isSymlink())
        assertEquals("after\n", log.readRaw())
        assertEquals("", outside.readRaw())
    }
}
