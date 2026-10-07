package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.files.Path

private const val HOLD_MILLIS = 5_000L
private const val GIVE_UP_BUDGET_MILLIS = 3_000L

/**
 * [withLock] promises that waiting is bounded, for a holder in this process as much as for one in another. An
 * unbounded wait ends in success rather than in an error, so timing the attempt is the only thing that tells
 * the two apart.
 */
class FileLocksJvmTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a holder in another process is LockBusy within the wait instead of blocking`() {
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(lockFile, marker)

        try {
            awaitHeld(marker, holder)
            val startedAt = System.nanoTime()

            val outcome = lockFile.withLock { "the block ran" }

            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMillis < GIVE_UP_BUDGET_MILLIS, "gave up after ${elapsedMillis}ms, not on its own wait")
            val busy = outcome.assertError<FileError.LockBusy>()
            assertNull(busy.holderPid)
            assertTrue(busy.waitedMs >= DEFAULT_LOCK_WAIT_MILLIS, "waited ${busy.waitedMs}ms")
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `a holder on another thread is LockBusy within the wait instead of queueing`() {
        val held = CountDownLatch(1)
        val holder = thread {
            lockFile.withLock {
                held.countDown()
                Thread.sleep(HOLD_MILLIS)
            }
        }

        try {
            check(held.await(30, TimeUnit.SECONDS)) { "the holding thread never took the lock" }
            val startedAt = System.nanoTime()

            val outcome = lockFile.withLock { "the block ran" }

            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMillis < GIVE_UP_BUDGET_MILLIS, "gave up after ${elapsedMillis}ms, not on its own wait")
            outcome.assertError<FileError.LockBusy>()
        } finally {
            holder.join()
        }
    }

    @Test
    fun `an interrupted wait behind another thread is LockBusy and leaves the interrupt set`() {
        val held = CountDownLatch(1)
        val holder = thread {
            lockFile.withLock {
                held.countDown()
                Thread.sleep(HOLD_MILLIS)
            }
        }

        try {
            check(held.await(30, TimeUnit.SECONDS)) { "the holding thread never took the lock" }

            assertInterruptedWaitIsBusy()
        } finally {
            holder.join()
        }
    }

    @Test
    fun `an interrupted wait behind another process is LockBusy and leaves the interrupt set`() {
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(lockFile, marker)

        try {
            awaitHeld(marker, holder)

            assertInterruptedWaitIsBusy()
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    /** Waits on [lockFile] for far longer than the holder holds it, from a thread that is interrupted meanwhile. */
    private fun assertInterruptedWaitIsBusy() {
        var outcome: Any? = null
        var stillInterrupted = false
        val waiter = thread {
            outcome = lockFile.withLock(waitMillis = 60_000) { "the block ran" }
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        Thread.sleep(200)
        waiter.interrupt()
        waiter.join(GIVE_UP_BUDGET_MILLIS)

        check(!waiter.isAlive) { "the interrupted wait did not end" }
        (outcome as com.fromwau.kern.result.Result<*, *>).assertError<FileError.LockBusy>()
        assertTrue(stillInterrupted, "the wait swallowed the interrupt")
    }

    @Test
    fun `a run another process is still running is not stale`() {
        val base = BaseDirs(dir, dir / "c", dir / "d", dir / "s", dir / "k", tempHome = dir / "tmp")
        val running = base.forApp("app").assertSuccess()
        val doctor = base.forApp("app").assertSuccess()
        File(running.temp.toString()).mkdirs()
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(running.temp / ".run.lock", marker)

        try {
            awaitHeld(marker, holder)

            assertEquals(Ok(emptyList()), doctor.staleTempRuns())
        } finally {
            holder.destroyForcibly().waitFor()
        }
        assertEquals(Ok(listOf(running.temp)), doctor.staleTempRuns())
    }

    @Test
    fun `a deleted lock file lets a second holder in while the first still holds it`() {
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(lockFile, marker)

        try {
            awaitHeld(marker, holder)
            check(File(lockFile.toString()).delete()) { "the lock file was not there to delete" }

            // A lock is held on the file that was open when it was taken, so the new file at the path is nobody's.
            assertEquals(Ok("the block ran"), lockFile.withLock { "the block ran" })
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    private fun spawnHolder(lockFile: Path, marker: File): Process {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        return ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            ForeignLockHolder::class.java.name,
            lockFile.toString(),
            marker.absolutePath,
            HOLD_MILLIS.toString(),
        ).redirectErrorStream(true).start()
    }

    /** Only once the marker lands is the lock genuinely held; starting sooner would test nothing. */
    private fun awaitHeld(marker: File, holder: Process) {
        val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
        while (!marker.exists() && System.nanoTime() < deadline) {
            check(holder.isAlive) { "the lock holder died: ${holder.inputStream.readBytes().decodeToString()}" }
            Thread.sleep(20)
        }
        check(marker.exists()) { "the lock holder never reported holding the lock" }
    }
}
