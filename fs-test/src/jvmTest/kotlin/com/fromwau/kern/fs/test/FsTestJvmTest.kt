package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class FsTestJvmTest {
    private val dir = scratchDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    @Test
    fun `setModifiedAt of a file of this user with mode 000 is WriteFailed`() {
        val file = dir / "locked.txt"
        file.writeText("a").assertSuccess()
        if (Files.getAttribute(Paths.get(file.toString()), "unix:uid") == 0) return
        file.chmod(0).assertSuccess()

        file.setModifiedAt(Instant.fromEpochSeconds(981_173_106)).assertError<FileError.WriteFailed>()
    }

    @Test
    fun `an interrupt while making a file is rethrown with the thread still interrupted`() {
        val interrupt = InterruptedException("stop")

        val thrown = assertFailsWith<InterruptedException> { made(dir / "fifo") { throw interrupt } }

        assertSame(interrupt, thrown)
        assertTrue(Thread.interrupted(), "the interrupt was cleared for whoever asked this thread to stop")
    }
}
