package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR

class TempRunsLinuxTest {
    private val dir = newTempDir()
    private val app = BaseDirs(
        home = dir,
        configHome = dir / "config",
        dataHome = dir / "data",
        stateHome = dir / "state",
        cacheHome = dir / "cache",
        tempHome = dir / "tmp",
    ).forApp("app").assertSuccess()
    private val root = checkNotNull(app.temp.parent)
    private val crashed = root / Uuid.random().toString()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a run temp another process claims is RunTempClaimed naming that process and creates no folder`() {
        createDirectoriesRaw(root)
        val holder = holdLockInChild(root / ".${app.temp.name}.lock", dir / "held")

        try {
            assertEquals(Err(FileError.RunTempClaimed(app.temp, holder.toLong())), app.withRunTemp { })
        } finally {
            stopLockHolder(holder)
        }
        assertFalse(SystemFileSystem.exists(app.temp))
    }

    @Test
    fun `the claim file is rw-r--r-- beside the owner's folder under a umask that lets the group write`() {
        val claim = root / ".${app.temp.name}.lock"

        val mode = withUmask(UMASK_002) { app.withRunTemp { claim.mode() } }

        assertEquals(S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH, mode.assertSuccess())
    }

    @Test
    fun `the folder holding the runs' folders follows the umask`() {
        withUmask(UMASK_077) { app.withRunTemp { } }.assertSuccess()

        assertEquals(S_IRWXU, root.mode())
    }

    @Test
    fun `a claim that cannot be created is WriteFailed naming the temp root`() {
        createDirectoriesRaw(root)
        assertClaimNotCreatable { app.withRunTemp { } }
    }

    @Test
    fun `listing stale runs when a claim cannot be created is WriteFailed naming the temp root`() {
        createDirectoriesRaw(crashed)
        assertClaimNotCreatable { app.staleTempRuns() }
    }

    @Test
    fun `clearing stale runs when a claim cannot be created is WriteFailed naming the temp root`() {
        createDirectoriesRaw(crashed)
        assertClaimNotCreatable { app.clearStaleTempRuns() }
        assertTrue(SystemFileSystem.exists(crashed))
    }

    @Test
    fun `a claim file this user may not open is LockFailed naming it`() {
        createDirectoriesRaw(root)
        val lockFile = (root / ".${app.temp.name}.lock").writeRaw("")
        assertLockFileRefused(lockFile) { app.withRunTemp { } }
    }

    @Test
    fun `listing stale runs lists the folders after one whose claim file may not be opened and fails that one`() {
        val first = root / Uuid.fromLongs(0, 0).toString()
        val last = root / Uuid.fromLongs(-1, -1).toString()
        createDirectoriesRaw(first)
        createDirectoriesRaw(last)
        val lockFile = (root / ".${first.name}.lock").writeRaw("")
        lockFile.setMode(0)
        try {
            // A runner that may open it anyway, such as root, leaves nothing to observe.
            if (lockFile.canOpenForReading()) return

            val runs = app.staleTempRuns().assertSuccess()

            assertEquals(listOf(last), runs.stale)
            assertEquals(listOf(first), runs.failed.map { it.folder })
            val failure = assertIs<FileError.LockFailed>(runs.failed.single().error)
            assertEquals(lockFile, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
        } finally {
            lockFile.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `clearing stale runs when a claim file may not be opened counts it as failed and clears nothing there`() {
        createDirectoriesRaw(crashed)
        val lockFile = (root / ".${crashed.name}.lock").writeRaw("")
        lockFile.setMode(0)
        try {
            // A runner that may open it anyway, such as root, leaves nothing to observe.
            if (lockFile.canOpenForReading()) return

            val cleanup = app.clearStaleTempRuns().assertSuccess()

            assertEquals(emptyList(), cleanup.cleared)
            assertEquals(listOf(crashed), cleanup.failed.map { it.folder })
            val failure = assertIs<FileError.LockFailed>(cleanup.failed.single().error)
            assertEquals(lockFile, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            assertTrue(SystemFileSystem.exists(crashed))
        } finally {
            lockFile.setMode(S_IRUSR or S_IWUSR)
        }
    }

    @Test
    fun `clearing stale runs clears the folders after one it cannot clear and fails that one on every call`() {
        val first = root / Uuid.fromLongs(0, 0).toString()
        val last = root / Uuid.fromLongs(-1, -1).toString()
        val locked = first / "locked"
        createDirectoriesRaw(first, OWNER_ONLY_FOLDER)
        createDirectoriesRaw(locked)
        val stuck = (locked / "file").writeRaw("")
        createDirectoriesRaw(last, OWNER_ONLY_FOLDER)
        locked.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, clears everything.
            if (locked.canCreateFileIn()) return

            val firstCall = app.clearStaleTempRuns().assertSuccess()
            val secondCall = app.clearStaleTempRuns().assertSuccess()

            assertEquals(listOf(last), firstCall.cleared)
            assertEquals(listOf(first), firstCall.failed.map { it.folder })
            assertEquals(listOf(stuck.realPath().assertSuccess()), firstCall.failed.map { it.error.path })
            assertIs<FileError.WriteFailed>(firstCall.failed.single().error)
            assertFalse(SystemFileSystem.exists(last))
            assertEquals(StaleCleanup(cleared = emptyList(), failed = firstCall.failed), secondCall)
        } finally {
            locked.setMode(S_IRWXU)
        }
    }

    /**
     * Checks that [claim] is [FileError.WriteFailed] naming the temp root, and the claim file it was refused at, once
     * that root may not be written.
     */
    private fun assertClaimNotCreatable(claim: () -> Result<Any?, FileError>) {
        root.setMode(S_IRUSR or S_IXUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (root.canCreateFileIn()) return

            val failure = claim().assertError<FileError.WriteFailed>()

            assertEquals(root, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            val claimFile = assertIs<FailedAt.Claim>(failure.at).path
            assertEquals(root, claimFile.parent)
            assertTrue(claimFile.name.endsWith(".lock"))
            assertEquals("$claimFile: Permission denied", failure.exception?.message)
        } finally {
            root.setMode(S_IRWXU)
        }
    }

    /** Checks that [claim] is [FileError.LockFailed] naming [lockFile] once that file has mode 000. */
    private fun assertLockFileRefused(
        lockFile: Path,
        claim: () -> Result<Any?, FileError>,
    ) {
        lockFile.setMode(0)
        try {
            // A runner that may open it anyway, such as root, leaves nothing to observe.
            if (lockFile.canOpenForReading()) return

            val failure = claim().assertError<FileError.LockFailed>()

            assertEquals(lockFile, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
            assertEquals("$lockFile: Permission denied", failure.exception?.message)
        } finally {
            lockFile.setMode(S_IRUSR or S_IWUSR)
        }
    }
}
