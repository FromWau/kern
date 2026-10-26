package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.linux.SYS_renameat2
import platform.posix.syscall

/** A folder swapped for a link while a [Writer] works never lets it act outside its root. */
@OptIn(ObsoleteWorkersApi::class, ExperimentalAtomicApi::class)
class WriterRaceLinuxTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val outside = (dir / "outside").also { createDirectoriesRaw(it) }
    private val worker = Worker.start()

    @AfterTest
    fun cleanUp() {
        worker.requestTermination().result
        dir.deleteTree()
    }

    @Test
    fun `a folder on the way swapped for a link leading out is never written through`() {
        val sub = (rootPath / "sub").also { createDirectoriesRaw(it) }
        val writer = rootPath.writer().assertSuccess()

        val swapping = swapRepeatedly(sub)
        val answers = mutableSetOf<String>()
        val swaps = swapping.countWhile {
            repeat(WRITES) { round -> answers += writer.writeText("sub/f$round", "x").kind() }
        }

        assertTrue(swaps > 0, "no swap ran while the writes did")
        assertEquals(emptyList(), SystemFileSystem.list(outside))
        assertTrue(answers.all { it in setOf("Ok", "Escapes", "Inaccessible", "NotADirectory") }, "$answers")
    }

    @Test
    fun `a root swapped for a link is never written through`() {
        val writer = rootPath.writer().assertSuccess()

        val swapping = swapRepeatedly(rootPath)
        val answers = mutableSetOf<String>()
        val swaps = swapping.countWhile {
            repeat(WRITES) { round -> answers += writer.writeText("f$round", "x").kind() }
        }

        assertTrue(swaps > 0, "no swap ran while the writes did")
        assertEquals(emptyList(), SystemFileSystem.list(outside))
        assertTrue(answers.all { it in setOf("Ok", "FolderReplaced") }, "$answers")
    }

    /**
     * Swaps [folder] with a link leading to the outside folder, and back, on the worker until [Swapping.countWhile]
     * stops it, each swap one exchange, so something is always at [folder] and the folder is back once it stops.
     */
    private fun swapRepeatedly(folder: Path): Swapping {
        val link = Path("$folder.link").rawSymlinkTo(outside)
        val swaps = Swaps(folder.toString(), link.toString())
        return Swapping(swaps, worker.execute(TransferMode.SAFE, { swaps }) { it.run() })
    }

    /** Exchanges [one] and [other] in pairs until [stop] is set, counting each exchange in [done]. */
    private class Swaps(
        val one: String,
        val other: String,
    ) {
        val stop = AtomicBoolean(false)
        val done = AtomicInt(0)

        fun run() {
            while (!stop.load()) {
                repeat(2) {
                    check(exchange(one, other) == 0L)
                    val _ = done.addAndFetch(1)
                }
            }
        }
    }

    /** [swaps] running on the worker until [countWhile] stops them, at which point [running] completes. */
    private class Swapping(
        val swaps: Swaps,
        val running: Future<Unit>,
    ) {
        /**
         * Runs [writes] once swapping has begun, then stops it, answering how many swaps ran during [writes]. A worker
         * that ended, or ran no swap within 10 seconds, stops the wait, and what it threw is thrown here.
         */
        fun countWhile(writes: () -> Unit): Int {
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (swaps.done.load() == 0 && running.state == FutureState.SCHEDULED && deadline.hasNotPassedNow()) {
                // Writes before the first swap would race with nothing.
            }
            if (swaps.done.load() == 0) {
                swaps.stop.store(true)
                running.result
                return 0
            }
            val before = swaps.done.load()
            writes()
            val after = swaps.done.load()
            swaps.stop.store(true)
            running.result
            return after - before
        }
    }

    /** `Ok`, or the simple name of the error, so a test can list which answers a race gave. */
    private fun Result<Any?, FileError>.kind(): String = when (this) {
        is Result.Success -> "Ok"
        is Result.Error -> error::class.simpleName.orEmpty()
    }

    private companion object {
        const val WRITES = 2000
    }
}

/** Swaps what is at [one] and [two] in one step, `renameat2` with `RENAME_EXCHANGE`, answering 0 or -1. */
@OptIn(ExperimentalForeignApi::class)
private fun exchange(
    one: String,
    two: String,
): Long = memScoped {
    syscall(SYS_renameat2.toLong(), AT_CWD, one.cstr.ptr, AT_CWD, two.cstr.ptr, RENAME_EXCHANGE)
}

private const val AT_CWD = -100L

private const val RENAME_EXCHANGE = 2L
