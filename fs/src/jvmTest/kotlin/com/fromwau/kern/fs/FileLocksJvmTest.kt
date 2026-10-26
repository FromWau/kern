package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

private val HOLD = 5.seconds
private val GIVE_UP_BUDGET = 3.seconds

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
    fun `a new lock file gives its group and others no write`() {
        lockFile.tryLock().assertSuccess().close()

        assertEquals(false, lockFile.permissions().assertSuccess()?.othersMayWrite)
    }

    @Test
    fun `a holder in another process is LockBusy within the wait instead of blocking`() {
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(lockFile, marker)

        try {
            awaitHeld(marker, holder)
            val started = TimeSource.Monotonic.markNow()

            val outcome = lockFile.withLock { "the block ran" }

            val elapsed = started.elapsedNow()
            assertTrue(elapsed < GIVE_UP_BUDGET, "gave up after $elapsed, not on its own wait")
            val busy = outcome.assertError<FileError.LockBusy>()
            assertNull(busy.holderPid)
            assertTrue(busy.waited >= DEFAULT_LOCK_WAIT, "waited ${busy.waited}")
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `tryLock behind another process is LockBusy naming no holder`() {
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(lockFile, marker)

        try {
            awaitHeld(marker, holder)

            val busy = FileError.LockBusy(lockFile, holderPid = null, waited = Duration.ZERO)
            assertEquals(Err(busy), lockFile.tryLock())
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `a holder in this process is named by this process's pid`() {
        lockFile.tryLock().assertSuccess().use {
            val busy = lockFile.tryLock().assertError<FileError.LockBusy>()

            assertEquals(ProcessHandle.current().pid(), busy.holderPid)
        }
    }

    @Test
    fun `a second name for a lock file this process holds is LockBusy naming this process`() {
        lockFile.tryLock().assertSuccess().use {
            val other = (dir / ".other.lock").rawHardLinkTo(lockFile)

            val busy = other.tryLock()
            busy.getOrNull()?.close()

            assertEquals(Err(FileError.LockBusy(other, ProcessHandle.current().pid(), waited = Duration.ZERO)), busy)
        }
    }

    @Test
    fun `a FIFO at the lock path is NotRegularFile`() {
        val fifo = dir / "pipe.lock"
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.withLock { })
        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.tryLock())
    }

    @Test
    fun `a lock file this user may not open is LockFailed as Denied`() {
        val file = lockFile.writeRaw("")
        File(file.toString()).setWritable(false)
        assumeFalse(File(file.toString()).canWrite(), "this user writes files whatever their mode")

        assertEquals(IoCause.Denied, file.withLock { }.assertError<FileError.LockFailed>().cause)
    }

    @Test
    fun `a holder on another thread is LockBusy within the wait instead of queueing`() {
        val held = CountDownLatch(1)
        val holder = thread {
            val _ = lockFile.withLock {
                held.countDown()
                Thread.sleep(HOLD.inWholeMilliseconds)
            }
        }

        try {
            check(held.await(30, TimeUnit.SECONDS)) { "the holding thread never took the lock" }
            val started = TimeSource.Monotonic.markNow()

            val outcome = lockFile.withLock { "the block ran" }

            val elapsed = started.elapsedNow()
            assertTrue(elapsed < GIVE_UP_BUDGET, "gave up after $elapsed, not on its own wait")
            outcome.assertError<FileError.LockBusy>()
        } finally {
            holder.join()
        }
    }

    @Test
    fun `an interrupted wait behind another thread is LockBusy and leaves the interrupt set`() {
        val held = CountDownLatch(1)
        val holder = thread {
            val _ = lockFile.withLock {
                held.countDown()
                Thread.sleep(HOLD.inWholeMilliseconds)
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
            outcome = lockFile.withLock(wait = 60.seconds) { "the block ran" }
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        Thread.sleep(200)
        waiter.interrupt()
        waiter.join(GIVE_UP_BUDGET.inWholeMilliseconds)

        check(!waiter.isAlive) { "the interrupted wait did not end" }
        (outcome as com.fromwau.kern.result.Result<*, *>).assertError<FileError.LockBusy>()
        assertTrue(stillInterrupted, "the wait swallowed the interrupt")
    }

    @Test
    fun `a run another process is still running is not stale`() {
        val base = BaseDirs(dir, dir / "c", dir / "d", dir / "s", dir / "k", tempHome = dir / "tmp")
        val running = base.forApp("app").assertSuccess()
        val doctor = base.forApp("app").assertSuccess()
        createDirectoriesRaw(running.temp)
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(dir / "tmp" / "app" / ".${running.temp.name}.lock", marker)

        try {
            awaitHeld(marker, holder)

            assertEquals(Ok(StaleRuns(stale = emptyList(), failed = emptyList())), doctor.staleTempRuns())
        } finally {
            holder.destroyForcibly().waitFor()
        }
        assertEquals(Ok(StaleRuns(listOf(running.temp), failed = emptyList())), doctor.staleTempRuns())
    }

    @Test
    fun `a run temp another process claims under a fixed run id is RunTempClaimed naming no holder`() {
        val base = BaseDirs(dir, dir / "c", dir / "d", dir / "s", dir / "k", tempHome = dir / "tmp")
        val app = base.forApp("app", Uuid.random()).assertSuccess()
        val runs = checkNotNull(app.temp.parent)
        createDirectoriesRaw(runs)
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(runs / ".${app.temp.name}.lock", marker)

        try {
            awaitHeld(marker, holder)

            assertEquals(Err(FileError.RunTempClaimed(app.temp, holderPid = null)), app.withRunTemp { })
        } finally {
            holder.destroyForcibly().waitFor()
        }
        assertFalse(app.temp.exists())
    }

    @Test
    fun `a run whose folder cannot be cleared names the entry it could not remove and keeps it`() {
        val base = BaseDirs(dir, dir / "c", dir / "d", dir / "s", dir / "k", tempHome = dir / "tmp")
        val app = base.forApp("app").assertSuccess()
        val sealed = File(app.temp.toString(), "sealed")
        try {
            val outcome = app.withRunTemp { temp ->
                sealed.mkdirs()
                (temp.root / "sealed" / "a.txt").writeRaw("a")
                sealed.setWritable(false)
                sealed.canWrite()
            }
            // A runner that may write anyway, such as root, cleared the folder and has nothing to observe.
            if (outcome == Ok(true)) return

            assertEquals(app.temp / "sealed" / "a.txt", outcome.assertError<FileError>().path)
            assertTrue(File(sealed, "a.txt").exists())
        } finally {
            sealed.setWritable(true)
        }
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

    @Test
    fun `singleInstance behind another process names the running pid in the pid file it left`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess()
        createDirectoriesRaw(app.state)
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(app.state / ".mirror.lock", marker)

        try {
            awaitHeld(marker, holder)
            (app.state / "mirror.pid").writeRaw("${holder.pid()}\n")

            val busy = FileError.LockBusy(app.state / ".mirror.lock", holder.pid(), waited = Duration.ZERO)
            assertEquals(Err(busy), app.singleInstance("mirror"))
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `singleInstance behind another process whose pid file names a process gone names no holder`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess()
        createDirectoriesRaw(app.state)
        val ended = ProcessBuilder(java, "-version").redirectErrorStream(true).start()
        val _ = ended.inputStream.readBytes()
        ended.waitFor()
        (app.state / "mirror.pid").writeRaw("${ended.pid()}\n")
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(app.state / ".mirror.lock", marker)

        try {
            awaitHeld(marker, holder)

            val busy = FileError.LockBusy(app.state / ".mirror.lock", holderPid = null, waited = Duration.ZERO)
            assertEquals(Err(busy), app.singleInstance("mirror"))
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    @Test
    fun `singleInstance behind another process whose pid file holds no pid names no holder`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess()
        createDirectoriesRaw(app.state)
        (app.state / "mirror.pid").writeRaw("not a pid")
        val marker = File(dir.toString(), "held")
        val holder = spawnHolder(app.state / ".mirror.lock", marker)

        try {
            awaitHeld(marker, holder)

            val busy = FileError.LockBusy(app.state / ".mirror.lock", holderPid = null, waited = Duration.ZERO)
            assertEquals(Err(busy), app.singleInstance("mirror"))
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    private val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath

    private fun spawnHolder(lockFile: Path, marker: File): Process = ProcessBuilder(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        ForeignLockHolder::class.java.name,
        lockFile.toString(),
        marker.absolutePath,
        HOLD.inWholeMilliseconds.toString(),
    ).redirectErrorStream(true).start()

    /** Only once the marker lands is the lock genuinely held; starting sooner would test nothing. */
    private fun awaitHeld(marker: File, holder: Process) {
        val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        while (!marker.exists() && deadline.hasNotPassedNow()) {
            check(holder.isAlive) { "the lock holder died: ${holder.inputStream.readBytes().decodeToString()}" }
            Thread.sleep(20)
        }
        check(marker.exists()) { "the lock holder never reported holding the lock" }
    }
}
