package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalKernApi::class)
class LockPausesTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun millis(vararg each: Int) = each.map { it.milliseconds }

    @Test
    fun `the pauses double from 10 ms and the last is cut to end at the wait`() {
        assertEquals(millis(10, 20, 40, 80, 150), lockPauses(300.milliseconds).toList())
        assertEquals(millis(10, 15), lockPauses(25.milliseconds).toList())
    }

    @Test
    fun `the pauses of an endless wait reach 500 ms and stay there`() {
        assertEquals(millis(10, 20, 40, 80, 160, 320, 500, 500, 500), lockPauses(Duration.INFINITE).take(9).toList())
    }

    @Test
    fun `a wait of zero or less pauses never`() {
        assertEquals(emptyList(), lockPauses(Duration.ZERO).toList())
        assertEquals(emptyList(), lockPauses((-1).seconds).toList())
    }

    @Test
    fun `a wait of a minute makes 126 attempts and pauses the whole minute`() {
        var attempts = 0
        val taken = mutableListOf<Duration>()

        val answer = retryWhileLockBusy(60.seconds, pause = { taken.add(it) }) {
            attempts++
            Err(FileError.LockBusy(lockFile, holderPid = null, waited = Duration.ZERO))
        }

        assertEquals(60.seconds, answer.assertError<FileError.LockBusy>().waited)
        assertEquals(126, attempts)
        assertEquals(60.seconds, taken.fold(Duration.ZERO, Duration::plus))
    }

    @Test
    fun `a pause that stops early ends the wait with the pauses taken so far`() {
        var pauses = 0

        val answer = retryWhileLockBusy(1.seconds, pause = { ++pauses < 3 }) {
            Err(FileError.LockBusy(lockFile, holderPid = null, waited = Duration.ZERO))
        }

        assertEquals(30.milliseconds, answer.assertError<FileError.LockBusy>().waited)
    }

    @Test
    fun `withLock on a held lock waits the sum of its pauses`() {
        lockFile.tryLock().assertSuccess().use {
            val busy = lockFile.withLock(wait = 25.milliseconds) { }.assertError<FileError.LockBusy>()

            assertEquals(25.milliseconds, busy.waited)
        }
        assertEquals(Ok(Unit), lockFile.withLock { })
    }
}
