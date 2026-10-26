package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.list
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.fs.tryLock
import com.fromwau.kern.fs.withLock
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
        val held = lockFile.tryLock().assertSuccess()
        launch {
            delay(100)
            held.close()
        }

        assertEquals(Ok("got it"), lockFile.awaitLock(wait = 500.milliseconds) { "got it" })
    }

    @Test
    fun `a holder that keeps it past the wait is LockBusy naming the holder as tryLock does`() = runTest {
        val held = lockFile.tryLock().assertSuccess()

        val busy = lockFile.awaitLock(wait = 100.milliseconds) { }.assertError<FileError.LockBusy>()

        val named = lockFile.tryLock().assertError<FileError.LockBusy>().holderPid
        assertNotNull(named)
        assertEquals(named, busy.holderPid)
        assertTrue(busy.waited >= 100.milliseconds)
        held.close()
    }

    @Test
    fun `a wait of 70 ms on a held lock suspends for 70 ms and is LockBusy having waited them`() = runTest {
        val held = lockFile.tryLock().assertSuccess()
        val start = testScheduler.timeSource.markNow()

        val busy = lockFile.awaitLock(wait = 70.milliseconds) { }.assertError<FileError.LockBusy>()

        assertEquals(70.milliseconds, start.elapsedNow())
        assertEquals(70.milliseconds, busy.waited)
        held.close()
    }

    @Test
    fun `a wait of a minute on a held lock suspends for the whole minute`() = runTest {
        val held = lockFile.tryLock().assertSuccess()
        val start = testScheduler.timeSource.markNow()

        val busy = lockFile.awaitLock(wait = 60.seconds) { }.assertError<FileError.LockBusy>()

        assertEquals(60.seconds, start.elapsedNow())
        assertEquals(60.seconds, busy.waited)
        held.close()
    }

    @Test
    fun `a negative wait tries once`() = runTest {
        val held = lockFile.tryLock().assertSuccess()

        val busy = lockFile.awaitLock(wait = (-1).milliseconds) { }.assertError<FileError.LockBusy>()

        assertEquals(Duration.ZERO, busy.waited)
        held.close()
    }

    @Test
    fun `what the block throws is rethrown with the lock released`() = runTest {
        assertFailsWith<IllegalStateException> { lockFile.awaitLock { error("the work failed") } }

        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a cancelled block releases the lock`() = runTest {
        val holder = launch { val _ = lockFile.awaitLock { awaitCancellation() } }
        yield()
        lockFile.tryLock().assertError<FileError.LockBusy>()

        holder.cancelAndJoin()

        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a cancelled wait holds nothing`() = runTest {
        val held = lockFile.tryLock().assertSuccess()
        val waiter = launch { val _ = lockFile.awaitLock(wait = 10_000.milliseconds) { } }
        yield()

        waiter.cancelAndJoin()
        held.close()

        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a folder at the lock path is NotRegularFile and runs nothing`() = runTest {
        val folder = dir / "blocked.lock"
        SystemFileSystem.createDirectories(folder)
        var ran = false

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), folder.awaitLock { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `an empty path is NotFound and runs nothing`() = runTest {
        var ran = false

        assertEquals(Err(FileError.NotFound(Path(""))), Path("").awaitLock { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `under runTest the wait for a holder on another thread passes on virtual time and is LockBusy`() = runTest {
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lockFile.withLock {
                holding.complete(Unit)
                release.await()
            }.assertSuccess()
        }
        holding.await()

        val busy = lockFile.awaitLock(wait = 2.seconds) { }.assertError<FileError.LockBusy>()

        release.complete(Unit)
        holder.join()
        assertTrue(busy.waited >= 2.seconds)
    }

    @Test
    fun `on Dispatchers Default under runTest the wait for a holder on another thread runs on real time`() = runTest {
        val holding = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            lockFile.withLock {
                holding.complete(Unit)
                delay(100)
            }.assertSuccess()
        }
        holding.await()

        val got = withContext(Dispatchers.Default) { lockFile.awaitLock(wait = 10.seconds) { "got it" } }

        holder.join()
        assertEquals(Ok("got it"), got)
    }

    /** The several steps under a lock from fs's docs/locking.md, as written there. */
    private suspend fun merged(
        defaults: Path,
        overrides: Path,
    ): Result<String, FileError> {
        val merged: Result<String, FileError> = result {
            lockFile.awaitLock {
                val base = defaults.readText(maxBytes = 65_536).bind()
                val local = overrides.readText(maxBytes = 65_536).bind()
                base + local
            }.bind()
        }
        return merged
    }

    @Test
    fun `a failed step under the lock ends the result block with its error and releases the lock`() = runTest {
        val defaults = dir / "defaults.toml"
        defaults.writeText("a = 1\n").assertSuccess()
        val overrides = dir / "overrides.toml"

        assertEquals(Err(FileError.NotFound(overrides)), merged(defaults, overrides))
        overrides.writeText("b = 2\n").assertSuccess()
        assertEquals(Ok("a = 1\nb = 2\n"), merged(defaults, overrides))
    }
}
