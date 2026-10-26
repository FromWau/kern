package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.Permissions
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.getuid
import platform.posix.umask
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class FsTestPosixTest {
    private val dir = scratchDir()
    private val loop = dir / "loop"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    @Test
    fun `chmod through a symlink loop is WriteFailed with the cause Loop`() {
        loop.symlinkTo(loop).assertSuccess()

        assertEquals(IoCause.Loop, loop.chmod(0b110_000_000).assertError<FileError.WriteFailed>().cause)
    }

    @Test
    fun `hardLinkTo and mkfifo in a folder that is a symlink loop are Inaccessible with the cause Loop`() {
        loop.symlinkTo(loop).assertSuccess()
        val file = dir / "a.txt"
        file.writeText("a").assertSuccess()

        assertEquals(IoCause.Loop, (loop / "b.txt").hardLinkTo(file).assertError<FileError.Inaccessible>().cause)
        assertEquals(IoCause.Loop, (loop / "pipe").mkfifo().assertError<FileError.Inaccessible>().cause)
    }

    @Test
    fun `chmod of a file this user does not own is WriteFailed with the cause Denied`() {
        if (getuid() == 0u) return
        val passwd = Path("/etc/passwd")

        assertEquals(Err(FileError.WriteFailed(passwd, IoCause.Denied, null)), passwd.chmod(0b110_000_000))
    }

    @Test
    fun `setModifiedAt sets the time of a file of this user with mode 000`() {
        if (getuid() == 0u) return
        val file = dir / "locked.txt"
        file.writeText("a").assertSuccess()
        file.chmod(0).assertSuccess()

        assertEquals(Ok(Unit), file.setModifiedAt(Instant.fromEpochSeconds(981_173_106)))
    }

    @Test
    @OptIn(ExperimentalForeignApi::class)
    fun `scratchDir is 755 under a umask of 077 and of 002`() {
        for (mask in listOf(0b000_111_111, 0b000_000_010)) {
            val previous = umask(mask.convert())
            val made = try {
                scratchDir()
            } finally {
                umask(previous)
            }
            try {
                assertEquals(Permissions(0b111_101_101), made.permissions().assertSuccess(), "under umask $mask")
            } finally {
                made.deleteRecursively().assertSuccess()
            }
        }
    }
}
