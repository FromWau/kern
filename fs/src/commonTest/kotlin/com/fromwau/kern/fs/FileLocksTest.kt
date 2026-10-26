package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class FileLocksTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `withLock runs the block and gives back what it returned`() {
        assertEquals(Ok("ran"), lockFile.withLock { "ran" })
    }

    @Test
    fun `the lock file and its missing folders are created and stay`() {
        val nested = dir / "a" / "b" / ".app.lock"

        nested.withLock { }.assertSuccess()

        assertTrue(nested.exists(), "deleting the lock file would let a second holder past the first")
    }

    @Test
    fun `a released lock lets the next holder in`() {
        lockFile.withLock { }.assertSuccess()

        assertEquals(Ok(2), lockFile.withLock { 2 })
    }

    @Test
    fun `a second hold on one lock file in this process is LockBusy`() {
        // The OS lock alone cannot do this: fcntl hands a process the lock it already holds.
        val outcome = lockFile.withLock { lockFile.withLock { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertEquals(lockFile, busy.path)
        assertNotNull(busy.holderPid, "a holder in this process goes unnamed")
        assertTrue(busy.waited >= DEFAULT_LOCK_WAIT, "waited ${busy.waited}")
    }

    @Test
    fun `one lock file spelled two ways is one lock`() {
        val spelledAgain = dir / "." / ".app.lock"

        val outcome = lockFile.withLock(wait = Duration.ZERO) { spelledAgain.withLock(wait = Duration.ZERO) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertEquals(spelledAgain, busy.path)
    }

    @Test
    fun `a wait of zero refuses a lock already held at once`() {
        val outcome = lockFile.withLock(wait = Duration.ZERO) { lockFile.withLock(wait = Duration.ZERO) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertTrue(busy.waited < DEFAULT_LOCK_WAIT, "waited ${busy.waited}")
    }

    @Test
    fun `a negative wait tries once as zero does`() {
        val outcome = lockFile.withLock(wait = Duration.ZERO) { lockFile.withLock(wait = (-1).milliseconds) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertTrue(busy.waited < DEFAULT_LOCK_WAIT, "waited ${busy.waited}")
    }

    @Test
    fun `what the block throws is rethrown with the lock released`() {
        assertFailsWith<IllegalStateException> { lockFile.withLock { error("the work failed") } }

        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a raised wait is what a refusal waits out`() {
        val raised = DEFAULT_LOCK_WAIT + 200.milliseconds

        val outcome = lockFile.withLock(raised) { lockFile.withLock(raised) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertTrue(busy.waited >= raised, "waited ${busy.waited} of a $raised budget")
    }

    @Test
    fun `a folder at the lock path is NotRegularFile`() {
        val folder = dir / "blocked.lock"
        createDirectoriesRaw(folder)

        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), folder.withLock { })
        assertEquals(Err(FileError.NotRegularFile(folder, FileType.Directory)), folder.tryLock())
    }

    @Test
    fun `a lock file whose folder cannot be created is an error rather than a throw`() {
        val file = (dir / "file").writeRaw("x")

        (file / ".app.lock").withLock { }.assertError<FileError.NotADirectory>()
        (file / ".app.lock").tryLock().assertError<FileError.NotADirectory>()
    }

    @Test
    fun `tryLock holds the lock until it is closed`() {
        val held = lockFile.tryLock().assertSuccess()

        assertEquals(lockFile, held.path)
        lockFile.tryLock().assertError<FileError.LockBusy>()
        lockFile.withLock(wait = Duration.ZERO) { }.assertError<FileError.LockBusy>()

        held.close()

        assertEquals(Ok(3), lockFile.withLock { 3 })
    }

    @Test
    fun `closing a lock twice releases it once`() {
        val first = lockFile.tryLock().assertSuccess()
        first.close()
        val second = lockFile.tryLock().assertSuccess()

        first.close()

        val third = lockFile.tryLock()
        third.getOrNull()?.close()
        second.close()
        third.assertError<FileError.LockBusy>()
    }

    @Test
    fun `tryLock on a lock this process holds is LockBusy naming this process with no wait`() {
        lockFile.tryLock().assertSuccess().use {
            val busy = lockFile.tryLock().assertError<FileError.LockBusy>()
            val waited = lockFile.withLock(wait = Duration.ZERO) { }.assertError<FileError.LockBusy>()

            assertEquals(FileError.LockBusy(lockFile, waited.holderPid, waited = Duration.ZERO), busy)
            assertNotNull(busy.holderPid)
        }
    }
}
