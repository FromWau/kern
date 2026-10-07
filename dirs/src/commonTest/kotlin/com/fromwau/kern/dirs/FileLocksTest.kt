package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.io.files.SystemFileSystem

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

        nested.withLock { }

        assertTrue(nested.exists(), "deleting the lock file would let a second holder past the first")
    }

    @Test
    fun `a released lock lets the next holder in`() {
        lockFile.withLock { }

        assertEquals(Ok(2), lockFile.withLock { 2 })
    }

    @Test
    fun `a second hold on one lock file in this process is LockBusy`() {
        // The OS lock alone cannot do this: fcntl hands a process the lock it already holds.
        val outcome = lockFile.withLock { lockFile.withLock { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertEquals(lockFile, busy.path)
        assertNull(busy.holderPid)
        assertTrue(busy.waitedMs >= DEFAULT_LOCK_WAIT_MILLIS, "waited ${busy.waitedMs}ms")
    }

    @Test
    fun `one lock file spelled two ways is one lock`() {
        val spelledAgain = dir / "." / ".app.lock"

        val outcome = lockFile.withLock(waitMillis = 0) { spelledAgain.withLock(waitMillis = 0) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertEquals(spelledAgain, busy.path)
    }

    @Test
    fun `a wait of zero refuses a lock already held at once`() {
        val outcome = lockFile.withLock(waitMillis = 0) { lockFile.withLock(waitMillis = 0) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertTrue(busy.waitedMs < DEFAULT_LOCK_WAIT_MILLIS, "waited ${busy.waitedMs}ms")
    }

    @Test
    fun `a raised wait is what a refusal waits out`() {
        val raised = DEFAULT_LOCK_WAIT_MILLIS + 200

        val outcome = lockFile.withLock(raised) { lockFile.withLock(raised) { } }

        val busy = outcome.assertSuccess().assertError<FileError.LockBusy>()
        assertTrue(busy.waitedMs >= raised, "waited ${busy.waitedMs}ms of a ${raised}ms budget")
    }

    @Test
    fun `a lock file that cannot be opened is LockFailed`() {
        // A folder cannot be opened for writing, so the attempt fails before any lock is taken.
        val folder = dir / "blocked.lock"
        SystemFileSystem.createDirectories(folder)

        folder.withLock { }.assertError<FileError.LockFailed>()
    }
}
