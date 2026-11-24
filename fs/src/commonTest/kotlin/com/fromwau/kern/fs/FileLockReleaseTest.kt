package com.fromwau.kern.fs

import com.fromwau.kern.result.assertSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** A lock whose OS release throws, as `FileChannel.close` can on the JVM. */
@OptIn(InternalKernApi::class)
class FileLockReleaseTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun lockThatWillNotRelease(): FileLock = lockFile
        .attemptLock(gate = lockFile.lockGate().assertSuccess(), unopened = ::lockFailed) { heldNeverReleased() }
        .assertSuccess()

    private fun heldNeverReleased() = OsLock.Held(key = null) { throw IOException("the OS kept the lock") }

    private class Boom : RuntimeException("the work failed")

    @Test
    fun `closing a lock the OS will not release does not throw`() {
        lockThatWillNotRelease().close()
    }

    @Test
    fun `a block that returns while the release fails answers its value and reports the failure`() {
        val reported = reportedReleaseFailures {
            assertEquals("ran", lockThatWillNotRelease().runThenRelease { "ran" })
        }

        assertEquals(listOf(lockFile to "the OS kept the lock"), reported)
        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a return out of the block while the release fails reports the failure`() {
        val lock = lockThatWillNotRelease()

        val reported = reportedReleaseFailures { assertEquals("early", returnedFrom(lock)) }

        assertEquals(listOf(lockFile to "the OS kept the lock"), reported)
    }

    private fun returnedFrom(lock: FileLock): String {
        lock.runThenRelease { return "early" }
    }

    /**
     * Runs [work] with [FileLocks.onReleaseFailed] recording each failed release in place of printing it, and answers
     * the lock files and failure messages it was told of.
     */
    private fun reportedReleaseFailures(work: () -> Unit): List<Pair<Path, String?>> {
        val reported = mutableListOf<Pair<Path, String?>>()
        val before = FileLocks.onReleaseFailed
        FileLocks.onReleaseFailed = { file, failure -> reported += file to failure.message }
        try {
            work()
        } finally {
            FileLocks.onReleaseFailed = before
        }
        return reported
    }

    @Test
    fun `a throw from the block is rethrown as it is and its failed release is not reported`() {
        val boom = Boom()

        val reported = reportedReleaseFailures {
            val thrown = assertFailsWith<Boom> { lockThatWillNotRelease().runThenRelease { throw boom } }
            assertSame(boom, thrown)
        }

        assertEquals(emptyList(), reported)
    }

    @Test
    fun `a cancelled block stays cancelled when the release fails`() {
        val cancelled = CancellationException("cancelled")

        val thrown = assertFailsWith<CancellationException> {
            lockThatWillNotRelease().runThenRelease { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun `a throw from a build is rethrown as it is when its stage's claim will not release`() {
        val boom = Boom()
        val site = dir / "site"

        val thrown = assertFailsWith<Boom> {
            site.buildInStage(site.makeStage { heldNeverReleased() }) { throw boom }
        }

        assertSame(boom, thrown)
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }
}
