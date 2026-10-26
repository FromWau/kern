package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.SystemFileSystem

class LockCheckTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a lock whose file is still at its path is held`() {
        lockFile.tryLock().assertSuccess().use {
            assertEquals(Ok(Unit), it.check())
        }
    }

    @Test
    fun `a lock whose file was deleted is LockLost`() {
        lockFile.tryLock().assertSuccess().use {
            SystemFileSystem.delete(lockFile)

            assertEquals(Err(FileError.LockLost(lockFile)), it.check())
        }
    }

    @Test
    fun `a lock whose file was deleted and made again at its path is LockLost`() {
        lockFile.tryLock().assertSuccess().use {
            SystemFileSystem.delete(lockFile)
            lockFile.writeRaw("")

            assertEquals(Err(FileError.LockLost(lockFile)), it.check())
        }
    }

    @Test
    fun `a closed lock is Ok whatever is at its path`() {
        val lock = lockFile.tryLock().assertSuccess()
        lock.close()
        SystemFileSystem.delete(lockFile)

        assertEquals(Ok(Unit), lock.check())
    }

    @Test
    fun `a lock whose folder was removed is LockLost`() {
        val nested = dir / "state" / ".app.lock"

        nested.tryLock().assertSuccess().use {
            (dir / "state").deleteRecursively().assertSuccess()

            assertEquals(Err(FileError.LockLost(nested)), it.check())
        }
    }
}
