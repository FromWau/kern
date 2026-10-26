package com.fromwau.kern.fs

import com.fromwau.kern.result.assertSuccess
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import platform.posix.unlink

/** A log another thread deletes while it is being appended to is reopened as [AppendState.Deleted], never Moved. */
@OptIn(ObsoleteWorkersApi::class, ExperimentalAtomicApi::class)
class AppendDeleteRaceLinuxTest {
    private val dir = newTempDir()
    private val worker = Worker.start()

    @AfterTest
    fun cleanUp() {
        worker.requestTermination().result
        dir.deleteTree()
    }

    // Holds every run if state() reads the path first; the other order fails only for a delete landing between the two.
    @Test
    fun `a log deleted while appends run is reopened as Deleted every time`() {
        val replaced = (1..RUNS).map { run -> replacedWhenDeletedDuringAppends(dir / "app$run.log") }

        assertEquals(List(RUNS) { AppendState.Deleted }, replaced)
    }

    /** Appends to [file] until a reopen happens, while the worker deletes it once appends have begun. */
    private fun replacedWhenDeletedDuringAppends(file: Path): AppendState {
        val seen = mutableListOf<AppendState>()
        val log = file.openAppend(Reopen.WhenMoved(onReopen = { _, state -> seen += state })).assertSuccess()
        val deletion = Deletion(file.toString(), spins = Random.nextInt(SPINS))
        val deleting = worker.execute(TransferMode.SAFE, { deletion }) { it.run() }

        log.append(LINE).assertSuccess()
        deletion.appending.store(true)
        var appends = 0
        while (seen.isEmpty() && appends++ < MAX_APPENDS) log.append(LINE).assertSuccess()
        log.close()
        deleting.result

        return seen.single()
    }

    /** Deletes [path] once [appending] is set and [spins] idle rounds have passed. */
    private class Deletion(
        val path: String,
        val spins: Int,
    ) {
        val appending = AtomicBoolean(false)

        fun run() {
            while (!appending.load()) {
                // The delete must land among the appends, not before the first one.
            }
            var idle = 0
            repeat(spins) { idle += it }
            check(unlink(path) == 0) { "unlink $path after $idle" }
        }
    }

    private companion object {
        const val RUNS = 40
        const val SPINS = 20_000
        const val MAX_APPENDS = 1_000_000
        val LINE = "line\n".encodeToByteArray()
    }
}
