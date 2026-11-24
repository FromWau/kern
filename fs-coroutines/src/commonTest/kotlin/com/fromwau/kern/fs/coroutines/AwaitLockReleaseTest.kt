package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileLocks
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.result.Ok
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A lock [awaitLock] holds, whose release the OS refuses once [closeDescriptorOn] closed it behind the lock's back:
 * kern:fs's lock-holding code, inlined into this module, still tells a block that returned from one that threw.
 */
abstract class AwaitLockReleaseTest {
    private val dir = scratchDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        val _ = dir.deleteRecursively()
    }

    /** Closes the one descriptor this process has open on [file], so a later close of it fails. */
    abstract fun closeDescriptorOn(file: Path)

    private class Boom : RuntimeException("the work failed")

    private fun closeTheLocksDescriptor() = closeDescriptorOn(lockFile.realPath().assertSuccess())

    @Test
    fun `a block that suspends and returns while the release fails answers its value and reports the failure`() =
        runTest {
            val reported = reportedReleaseFailures {
                val answer = lockFile.awaitLock {
                    withContext(Dispatchers.Default) { yield() }
                    closeTheLocksDescriptor()
                    "ran"
                }
                assertEquals(Ok("ran"), answer)
            }

            assertEquals(listOf(lockFile), reported)
        }

    @Test
    fun `a throw from a block whose release fails is rethrown as it is and not reported`() = runTest {
        val boom = Boom()

        val reported = reportedReleaseFailures {
            val thrown = assertFailsWith<Boom> {
                lockFile.awaitLock {
                    withContext(Dispatchers.Default) { yield() }
                    closeTheLocksDescriptor()
                    throw boom
                }
            }
            assertSame(boom, thrown)
        }

        assertEquals(emptyList(), reported)
    }

    /** Runs [work] with [FileLocks.onReleaseFailed] recording the lock file of each failed release. */
    private inline fun reportedReleaseFailures(work: () -> Unit): List<Path> {
        val reported = mutableListOf<Path>()
        val before = FileLocks.onReleaseFailed
        FileLocks.onReleaseFailed = { file, _ -> reported += file }
        try {
            work()
        } finally {
            FileLocks.onReleaseFailed = before
        }
        return reported
    }
}
