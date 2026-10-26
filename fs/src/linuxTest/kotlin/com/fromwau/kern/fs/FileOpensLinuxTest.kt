@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.O_RDWR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWGRP
import platform.posix.S_IWOTH
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.open

class FileOpensLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a FIFO opened for reading through kern's own open is NotRegularFile`() = withOpenFifo { fifo ->
        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.openSource())
    }

    @Test
    fun `a FIFO opened for writing through kern's own open is refused as a FIFO`() = withOpenFifo { fifo ->
        val failure = assertFailsWith<NotRegularFileFailure> { openFileSink(fifo, append = false).close() }

        assertEquals(FileType.Fifo, failure.type)
    }

    @Test
    fun `a FIFO no one reads opened for writing through kern's own open is refused as a FIFO`() {
        val fifo = (dir / "pipe").makeFifo()

        val failure = assertFailsWith<NotRegularFileFailure> { openFileSink(fifo, append = true).close() }

        assertEquals(FileType.Fifo, failure.type)
    }

    @Test
    fun `a device opened for reading through kern's own open is NotRegularFile`() {
        val device = Path("/dev/null")

        assertEquals(Err(FileError.NotRegularFile(device, FileType.CharacterDevice)), device.openSource())
    }

    @Test
    fun `a write that takes no bytes of a non-empty buffer fails for Other instead of trying again`() {
        var calls = 0

        val file = dir / "a"

        val failure = writeAll("abc".encodeToByteArray(), file) { _, _ ->
            check(++calls < 3) { "asked to write again after nothing was written" }
            0L
        }

        assertEquals(1, calls)
        assertEquals(IoCause.Other, assertNotNull(failure).ioCause())
        assertEquals("$file: wrote none of the 3 bytes left", failure.message)
    }

    @Test
    fun `a file openAppend creates gets the mode a file a write creates gets`() {
        val appended = dir / "app.log"
        val written = dir / "app.toml"

        withUmask(UMASK_002) {
            appended
                .openAppend()
                .assertSuccess()
                .close()
            assertEquals(Ok(WriteWay.Renamed), written.writeText("").way())
        }

        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP or S_IROTH, written.mode())
        assertEquals(written.mode(), appended.mode())
    }

    @Test
    fun `an owner-only held file is created 0600 by its open before any mode is set`() {
        val file = dir / "held.tmp"

        val descriptor = withUmask(0) { createRegularFile(Opening(file), OWNER_READ_WRITE) }
        close(descriptor)

        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `an owner-only file opened for writing is created 0600 by its open before any mode is set`() {
        val file = dir / "written.toml"

        val descriptor = withUmask(0) { openForWriting(Opening(file), append = false, permissions = OWNER_READ_WRITE) }
        close(descriptor)

        assertEquals(S_IRUSR or S_IWUSR, file.mode())
    }

    @Test
    fun `a file opened for writing without ownerOnly is created with the mode the umask leaves`() {
        val file = dir / "written.toml"

        val descriptor = withUmask(0) { openForWriting(Opening(file), append = false, permissions = null) }
        close(descriptor)

        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IWGRP or S_IROTH or S_IWOTH, file.mode())
    }

    @Test
    fun `an owner-only folder is created 0700 whatever the umask lets other folders have`() {
        val folder = dir / "run"

        val created = withUmask(UMASK_022) { makeFolder(folder, OWNER_ONLY_FOLDER) }

        assertTrue(created)
        assertEquals(S_IRWXU, folder.mode())
    }

    @Test
    fun `an owner-only folder already there is left as it is and reported as not created`() {
        val folder = dir / "run"
        assertTrue(makeFolder(folder, OWNER_ONLY_FOLDER))
        folder.setMode(S_IRWXU or S_IRGRP)

        assertFalse(makeFolder(folder, OWNER_ONLY_FOLDER))
        assertEquals(S_IRWXU or S_IRGRP, folder.mode())
    }

    /** Runs [block] on a FIFO this test holds open at both ends, so no open of it waits for the other end. */
    private fun withOpenFifo(block: (Path) -> Unit) {
        val fifo = (dir / "pipe").makeFifo()
        val held = open(fifo.toString(), O_RDWR)
        check(held >= 0)
        try {
            block(fifo)
        } finally {
            close(held)
        }
    }

    @Test
    fun `accept is given the info of the file the read opened rather than one moved over its path since`() {
        val file = (dir / "a.txt").writeRaw("abc")
        val swapped = (dir / "b.txt").writeRaw("longer content")
        val given = mutableListOf<FileInfo>()

        val read = file.readWhole(
            maxBytes = null,
            accept = { info ->
                given += info
                true
            },
            onOpened = { SystemFileSystem.atomicMove(swapped, file) },
        )

        assertEquals("abc", read.assertSuccess().decodeToString())
        assertEquals(listOf(3L), given.map { it.size })
    }
}
