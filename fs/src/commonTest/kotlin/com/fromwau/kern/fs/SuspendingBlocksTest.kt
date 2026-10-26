package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

class SuspendingBlocksTest {
    private val dir = newTempDir()
    private val app: AppDirs = BaseDirs(
        home = dir,
        configHome = dir / "config",
        dataHome = dir / "data",
        stateHome = dir / "state",
        cacheHome = dir / "cache",
        tempHome = dir / "tmp",
    ).forApp("app", Uuid.random()).assertSuccess()
    private val claim: Path = checkNotNull(app.temp.parent) / ".${app.temp.name}.lock"
    private val lockFile = dir / "app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a withRunTemp block may suspend and its folder and claim go when it returns`() = runTest {
        val outcome = app.withRunTemp { temp ->
            delay(1_000)
            temp.root.exists()
        }

        assertEquals(Ok(true), outcome)
        assertFalse(app.temp.exists())
        assertFalse(claim.exists())
    }

    @Test
    fun `a withRunTemp block cancelled midway clears its folder and lets go of its claim`() = runTest {
        val started = CompletableDeferred<Unit>()
        val running = launch {
            val _ = app.withRunTemp {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        running.cancelAndJoin()

        assertFalse(app.temp.exists())
        assertFalse(claim.exists())
        claim.tryLock().assertSuccess().close()
    }

    @Test
    fun `a withLock block may suspend and the lock is free once it returns`() = runTest {
        val outcome = lockFile.withLock {
            delay(1_000)
            "done"
        }

        assertEquals(Ok("done"), outcome)
        lockFile.tryLock().assertSuccess().close()
    }

    @Test
    fun `a withLock block cancelled midway lets go of the lock`() = runTest {
        val started = CompletableDeferred<Unit>()
        val running = launch {
            val _ = lockFile.withLock {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        running.cancelAndJoin()

        lockFile.tryLock().assertSuccess().close()
    }
}
