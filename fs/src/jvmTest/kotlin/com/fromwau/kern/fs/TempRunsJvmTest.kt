package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Assumptions.assumeFalse

class TempRunsJvmTest {
    private val dir = newTempDir()
    private val base = BaseDirs(
        home = dir,
        configHome = dir / "config",
        dataHome = dir / "data",
        stateHome = dir / "state",
        cacheHome = dir / "cache",
        tempHome = dir / "tmp",
    )
    private val app = base.forApp("app").assertSuccess()
    private val root = checkNotNull(app.temp.parent)

    @AfterTest
    fun cleanUp() {
        File(root.toString()).setWritable(true)
        dir.deleteTree()
    }

    @Test
    fun `the run folder may be entered by its owner alone while the block runs`() {
        val permissions = app.withRunTemp { Files.getPosixFilePermissions(Paths.get(it.root.toString())) }

        assertEquals(Ok(PosixFilePermissions.fromString("rwx------")), permissions)
    }

    @Test
    fun `a claim that cannot be created is WriteFailed naming the temp root`() {
        createDirectoriesRaw(root)
        File(root.toString()).setWritable(false)
        assumeFalse(File(root.toString(), "probe").mkdir(), "this user writes into folders without write permission")

        val failure = app.withRunTemp { }.assertError<FileError.WriteFailed>()

        assertEquals(root, failure.path)
        assertEquals(IoCause.Denied, failure.cause)
        assertIs<AccessDeniedException>(failure.exception)
        assertEquals(root, assertIs<FailedAt.Claim>(failure.at).path.parent)
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
        File(locked.toString()).setWritable(false)
        try {
            val writable = File(locked.toString(), "probe").mkdir()
            assumeFalse(writable, "this user writes into folders without write permission")

            val firstCall = app.clearStaleTempRuns().assertSuccess()
            val secondCall = app.clearStaleTempRuns().assertSuccess()

            assertEquals(listOf(last), firstCall.cleared)
            assertEquals(listOf(first), firstCall.failed.map { it.folder })
            assertEquals(listOf(stuck), firstCall.failed.map { it.error.path })
            assertIs<FileError.WriteFailed>(firstCall.failed.single().error)
            assertEquals(StaleCleanup(cleared = emptyList(), failed = firstCall.failed), secondCall)
        } finally {
            File(locked.toString()).setWritable(true)
        }
    }

    @Test
    fun `listing stale runs when a claim cannot be created is WriteFailed naming the temp root`() {
        createDirectoriesRaw(root / Uuid.random().toString())
        File(root.toString()).setWritable(false)
        assumeFalse(File(root.toString(), "probe").mkdir(), "this user writes into folders without write permission")

        val failure = app.staleTempRuns().assertError<FileError.WriteFailed>()

        assertEquals(root, failure.path)
        assertEquals(IoCause.Denied, failure.cause)
        assertIs<AccessDeniedException>(failure.exception)
        assertEquals(root, assertIs<FailedAt.Claim>(failure.at).path.parent)
    }

    @Test
    fun `a cleanup running beside runs that start and finish clears none of them`() {
        val runFailures = ConcurrentLinkedQueue<Throwable>()
        val runs = List(8) {
            thread {
                try {
                    repeat(25) { base.forApp("app", Uuid.random()).assertSuccess().withRunTemp { }.assertSuccess() }
                } catch (e: Throwable) {
                    runFailures.add(e)
                }
            }
        }
        val cleanups = buildList {
            while (runs.any { it.isAlive }) add(app.clearStaleTempRuns().assertSuccess())
        }
        runs.forEach { it.join() }

        assertEquals(emptyList(), runFailures.toList())
        assertTrue(cleanups.isNotEmpty(), "no cleanup ran while the runs were alive")
        assertEquals(emptyList(), cleanups.filter { it.cleared.isNotEmpty() || it.failed.isNotEmpty() })
    }
}
