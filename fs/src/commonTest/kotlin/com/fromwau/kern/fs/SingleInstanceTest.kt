package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.files.SystemFileSystem

class SingleInstanceTest {
    private val dir = newTempDir()
    private val app: AppDirs = BaseDirs.under(dir).forApp("app").assertSuccess()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @Test
    fun `a pid file that is a link to a file in state is replaced by a file of its own`() {
        createDirectoriesRaw(app.state)
        val token = (app.state / "token").writeRaw("secret")
        links.make(app.state / "mirror.pid", token)

        app.singleInstance("mirror").assertSuccess().use {
            assertEquals(Ok(false), it.pidFile.isSymlink())
            assertEquals("${currentProcessId()}\n", it.pidFile.readRaw())
            it.pidFile.assertPermissions(0b110_000_000)
        }
        assertEquals("secret", token.readRaw())
    }

    @Test
    fun `a pid file that is a link leading out of state is replaced and what it leads to is left alone`() {
        createDirectoriesRaw(app.state)
        val outside = (dir / "outside.txt").writeRaw("theirs")
        links.make(app.state / "mirror.pid", outside)

        app.singleInstance("mirror").assertSuccess().use {
            assertEquals(Ok(false), it.pidFile.isSymlink())
            assertEquals("${currentProcessId()}\n", it.pidFile.readRaw())
        }
        assertEquals("theirs", outside.readRaw())
    }

    @Test
    fun `a lock file that is a link leading out of state is replaced and nothing is made where it led`() {
        createDirectoriesRaw(app.state)
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        val lockFile = links.make(app.state / ".mirror.lock", outside / ".mirror.lock")

        app.singleInstance("mirror").assertSuccess().close()

        assertEquals(Ok(false), lockFile.isSymlink())
        assertEquals(emptyList(), SystemFileSystem.list(outside))
    }

    @Test
    fun `singleInstance makes a missing state folder for its owner alone`() {
        app.singleInstance("mirror").assertSuccess().close()

        app.state.assertPermissions(0b111_000_000)
    }

    @Test
    fun `singleInstance refuses a state folder anybody may write and locks and writes nothing`() {
        createDirectoriesRaw(app.state)
        app.state.chmod(0b111_111_111).assertSuccess()
        val info = app.state.info().assertSuccess()
        // Windows keeps no permissions, which leaves nothing to observe.
        if (info.permissions == null) return

        val refused = Err(FileError.Refused(app.state, info, RefusedBecause.OthersMayWrite, app.state))
        assertEquals(refused, app.singleInstance("mirror"))
        assertEquals(refused, app.singleInstance(dir / "out"))
        assertEquals(emptyList(), SystemFileSystem.list(app.state))
    }

    @Test
    fun `singleInstance takes a state folder others may read and keeps its mode and makes its files for you alone`() {
        createDirectoriesRaw(app.state)
        app.state.chmod(0b111_101_101).assertSuccess()

        app.singleInstance("mirror").assertSuccess().use {
            it.lockFile.assertPermissions(0b110_000_000)
            it.pidFile.assertPermissions(0b110_000_000)
        }

        app.state.assertPermissions(0b111_101_101)
    }

    @Test
    fun `singleInstance refuses a state folder its group may write`() {
        createDirectoriesRaw(app.state)
        for (bits in listOf(0b111_111_000, 0b111_111_101)) {
            app.state.chmod(bits).assertSuccess()
            val info = app.state.info().assertSuccess()
            if (info.permissions == null) return

            val refused = Err(FileError.Refused(app.state, info, RefusedBecause.OthersMayWrite, app.state))
            assertEquals(refused, app.singleInstance("mirror"), "$bits")
        }
        assertEquals(emptyList(), SystemFileSystem.list(app.state))
    }

    @Test
    fun `singleInstance takes the lock in an existing state folder only its owner may use`() {
        createDirectoriesRaw(app.state)
        app.state.chmod(0b111_000_000).assertSuccess()

        app.singleInstance("mirror").assertSuccess().close()

        app.state.assertPermissions(0b111_000_000)
    }

    @Test
    fun `an instance whose lock file is deleted while held has lost its lock`() {
        app.singleInstance("mirror").assertSuccess().use {
            assertEquals(Ok(Unit), it.check())

            SystemFileSystem.delete(it.lockFile)

            assertEquals(Err(FileError.LockLost(it.lockFile)), it.check())
        }
    }

    @Test
    fun `an instance whose state folder is removed while held has lost its lock`() {
        app.singleInstance("mirror").assertSuccess().use {
            app.state.deleteRecursively().assertSuccess()

            assertEquals(Err(FileError.LockLost(it.lockFile)), it.check())
        }
    }

    @Test
    fun `singleInstance locks a dot lock file in state and leaves this process's pid beside it for you alone`() {
        val held = app.singleInstance("mirror").assertSuccess()

        held.use {
            assertEquals(app.state / ".mirror.lock", it.lockFile)
            assertEquals(app.state / "mirror.pid", it.pidFile)
            assertEquals("${currentProcessId()}\n", it.pidFile.readRaw())
            it.pidFile.assertPermissions(0b110_000_000)
            assertEquals(Err(FileError.LockBusy(it.lockFile, currentProcessId(), Duration.ZERO)), it.lockFile.tryLock())
        }
    }

    @Test
    fun `a second singleInstance while one is held is LockBusy naming the holder`() {
        app.singleInstance("mirror").assertSuccess().use {
            val busy = FileError.LockBusy(app.state / ".mirror.lock", currentProcessId(), Duration.ZERO)

            assertEquals(Err(busy), app.singleInstance("mirror"))
        }
    }

    @Test
    fun `closing the instance lock releases it and leaves the lock file and the pid file`() {
        val first = app.singleInstance("mirror").assertSuccess()
        first.close()

        val second = app.singleInstance("mirror")
        second.assertSuccess().close()

        assertTrue(SystemFileSystem.exists(first.lockFile))
        assertTrue(SystemFileSystem.exists(first.pidFile))
    }

    @Test
    fun `a pid file left by an earlier run is replaced`() {
        createDirectoriesRaw(app.state)
        (app.state / "mirror.pid").writeRaw("99999999999")

        app.singleInstance("mirror").assertSuccess().use {
            assertEquals("${currentProcessId()}\n", it.pidFile.readRaw())
        }
    }

    @Test
    fun `a name that is not one file name is InvalidName and creates nothing`() {
        assertEquals(
            Err(FileError.InvalidName(app.state / ".a/b.lock", NameProblem.Separator)),
            app.singleInstance("a/b"),
        )
        assertFalse(SystemFileSystem.exists(app.state))
    }

    @Test
    fun `a name whose lock file takes 255 bytes is locked`() {
        val name = "a".repeat(249)

        app.singleInstance(name).assertSuccess().use {
            assertEquals(app.state / ".$name.lock", it.lockFile)
        }
    }

    @Test
    fun `a name whose lock file would take 256 bytes is InvalidName TooLong and creates nothing`() {
        val name = "a".repeat(250)

        assertEquals(
            Err(FileError.InvalidName(app.state / ".$name.lock", NameProblem.TooLong)),
            app.singleInstance(name),
        )
        assertFalse(SystemFileSystem.exists(app.state))
    }

    @Test
    fun `an instance lock in a folder that cannot be made is the error making it gave`() {
        createDirectoriesRaw(dir / "state")
        val blocker = (dir / "state" / "app").writeRaw("a file where the state folder goes")

        assertEquals(Err(FileError.NotADirectory(blocker, FileType.Regular)), app.singleInstance("mirror"))
        assertEquals(Ok(FileType.Regular), blocker.fileType())
    }

    @Test
    fun `a pid file naming a process gone is read again for 100 ms and then names no holder`() {
        createDirectoriesRaw(app.state)
        val pidFile = (app.state / "mirror.pid").writeRaw("$NO_SUCH_PID\n")
        val pauses = mutableListOf<Duration>()

        assertNull(runningPidIn(pidFile, pause = { pauses += it }))
        assertEquals(100.milliseconds, pauses.fold(Duration.ZERO, Duration::plus))
    }

    @Test
    fun `a running pid written while the pid file is read again names that holder`() {
        createDirectoriesRaw(app.state)
        val pidFile = (app.state / "mirror.pid").writeRaw("$NO_SUCH_PID\n")

        val named = runningPidIn(pidFile, pause = { pidFile.writeRaw("${currentProcessId()}\n") })

        assertEquals(currentProcessId(), named)
    }

    @Test
    fun `a pid file naming a running process names it without a pause`() {
        createDirectoriesRaw(app.state)
        val pidFile = (app.state / "mirror.pid").writeRaw("${currentProcessId()}\n")

        assertEquals(currentProcessId(), runningPidIn(pidFile, pause = { error("paused for a running pid") }))
    }

    @Test
    fun `a pid file holding zero or a negative pid names no holder`() {
        createDirectoriesRaw(app.state)
        val pidFile = app.state / "mirror.pid"

        for (pid in listOf("0", "-1")) {
            pidFile.writeRaw("$pid\n")
            assertNull(runningPidIn(pidFile, pause = { }), "pid $pid")
        }
    }

    @Test
    fun `this process is running and a pid no process has is not`() {
        assertTrue(isProcessRunning(currentProcessId()))
        assertFalse(isProcessRunning(NO_SUCH_PID))
    }

    private companion object {
        /** Above every pid Linux and Apple hand out, and no multiple of four, as every Windows pid is. */
        const val NO_SUCH_PID = 99_999_999L
    }
}
