package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.tryLock
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AwaitLockTest {
    private val dir = SystemTemporaryDirectory / "kern-fs-coroutines-${Uuid.random()}"
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        val _ = dir.deleteRecursively()
    }

    @Test
    fun `runs a block that suspends and gives back what it returned`() = runTest {
        assertEquals(Ok("ran"), lockFile.awaitLock { yield().let { "ran" } })
    }

    @Test
    fun `waits by suspending until another holder lets go`() = runTest {
        val held = assertNotNull(lockFile.tryLock().assertSuccess())
        launch {
            delay(100)
            held.close()
        }

        assertEquals(Ok("got it"), lockFile.awaitLock(waitMillis = 500) { "got it" })
    }

    @Test
    fun `a holder that keeps it past the wait is LockBusy naming no holder`() = runTest {
        val held = assertNotNull(lockFile.tryLock().assertSuccess())

        val busy = lockFile.awaitLock(waitMillis = 100) { }.assertError<FileError.LockBusy>()

        assertNull(busy.holderPid)
        assertTrue(busy.waitedMs >= 100)
        held.close()
    }

    @Test
    fun `a cancelled block releases the lock`() = runTest {
        val holder = launch { val _ = lockFile.awaitLock { awaitCancellation() } }
        yield()
        assertEquals(Ok(null), lockFile.tryLock(), "the block never took the lock")

        holder.cancelAndJoin()

        assertNotNull(lockFile.tryLock().assertSuccess()).close()
    }

    @Test
    fun `a cancelled wait holds nothing`() = runTest {
        val held = assertNotNull(lockFile.tryLock().assertSuccess())
        val waiter = launch { val _ = lockFile.awaitLock(waitMillis = 10_000) { } }
        yield()

        waiter.cancelAndJoin()
        held.close()

        assertNotNull(lockFile.tryLock().assertSuccess()).close()
    }
}
