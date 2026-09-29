package com.fromwau.kern.dirs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class TempRunsTest {
    private val dir = newTempDir()
    private val base = BaseDirs(
        home = dir,
        configHome = dir / "config",
        dataHome = dir / "data",
        stateHome = dir / "state",
        cacheHome = dir / "cache",
        tempHome = dir / "tmp",
    )

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun run(): AppDirs = checkNotNull(base.forApp("app", Uuid.random()).getOrNull())

    @Test
    fun `withRunTemp creates the run's temp folder and gives back what the block returned`() {
        val app = run()

        val outcome = app.withRunTemp { temp ->
            assertEquals(app.temp, temp)
            temp.exists()
        }

        assertEquals(Ok(true), outcome)
    }

    @Test
    fun `a run that left its folder behind is stale and a running one is not`() {
        val crashed = run()
        val running = run()
        val doctor = run()
        crashed.withRunTemp { }

        val stale = running.withRunTemp { doctor.withRunTemp { doctor.staleTempRuns() } }

        assertEquals(Ok(Ok(Ok(listOf(crashed.temp)))), stale)
    }

    @Test
    fun `a run never lists its own folder`() {
        val app = run()

        assertEquals(Ok(Ok(emptyList<Path>())), app.withRunTemp { app.staleTempRuns() })
    }

    @Test
    fun `an app with no temp folders yet has no stale runs`() {
        assertEquals(Ok(emptyList()), run().staleTempRuns())
    }

    @Test
    fun `a second claim on one run id is LockBusy`() {
        val runId = Uuid.random()
        val first = checkNotNull(base.forApp("app", runId).getOrNull())
        val second = checkNotNull(base.forApp("app", runId).getOrNull())

        val outcome = first.withRunTemp { second.withRunTemp { } }

        assertIs<FileError.LockBusy>(outcome.getOrNull()?.errorOrNull())
        assertTrue(first.temp.exists())
    }
}
