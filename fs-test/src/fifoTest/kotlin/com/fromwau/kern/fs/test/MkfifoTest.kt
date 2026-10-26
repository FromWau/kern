package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.fileType
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MkfifoTest {
    private val dir = scratchDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    @Test
    fun `mkfifo makes a FIFO and never replaces anything`() {
        val fifo = dir / "pipe"

        fifo.mkfifo().assertSuccess()

        assertEquals(Ok(FileType.Fifo), fifo.fileType())
        assertEquals(Err(FileError.AlreadyExists(fifo, FileType.Fifo)), fifo.mkfifo())
    }

    @Test
    fun `mkfifo makes the missing folders on the way`() {
        val fifo = dir / "a" / "b" / "pipe"

        fifo.mkfifo().assertSuccess()

        assertEquals(Ok(FileType.Fifo), fifo.fileType())
    }

    @Test
    fun `mkfifo below a file is NotFound naming the folder it cannot make`() {
        val file = dir / "file"
        file.writeText("x").assertSuccess()

        assertEquals(Err(FileError.NotFound(file / "sub")), (file / "sub" / "pipe").mkfifo())
    }
}
