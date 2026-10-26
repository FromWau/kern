package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.NameProblem
import com.fromwau.kern.fs.RefusedBecause
import com.fromwau.kern.fs.Writer
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.currentProcessId
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.fs.singleInstance
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.withRunTemp
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory

class AwaitSingleInstanceTest {
    private val dir = SystemTemporaryDirectory / "kern-fs-coroutines-${Uuid.random()}"
    private val dirs = AppDirs(
        config = dir / "config",
        data = dir / "data",
        state = dir / "state",
        cache = dir / "cache",
        temp = dir / "tmp",
        runtime = null,
    )

    @AfterTest
    fun cleanUp() {
        val _ = dir.deleteRecursively()
    }

    // Real time on Dispatchers.Default for both sides, as the holder lets go on another thread.
    @Test
    fun `a second run waits for the first to let go and then runs`() = runTest {
        val first = dirs.singleInstance("app").assertSuccess()
        launch(Dispatchers.Default) {
            delay(100)
            first.close()
        }

        val got = withContext(Dispatchers.Default) { dirs.awaitSingleInstance("app", wait = 10.seconds) { "got it" } }

        assertEquals(Ok("got it"), got)
    }

    @Test
    fun `a holder that keeps it past the wait is LockBusy naming this process`() = runTest {
        val first = dirs.singleInstance("app").assertSuccess()
        var ran = false

        val busy = dirs
            .awaitSingleInstance("app", wait = 100.milliseconds) { ran = true }
            .assertError<FileError.LockBusy>()

        first.close()
        assertEquals(currentProcessId(), busy.holderPid)
        assertTrue(busy.waited >= 100.milliseconds)
        assertFalse(ran)
    }

    @Test
    fun `a key waits for the instance singleInstance holds for the same key under another spelling`() = runTest {
        val out = dir / "out"
        val first = dirs.singleInstance(out).assertSuccess()
        var ran = false

        val busy = dirs
            .awaitSingleInstance(dir / "src" / ".." / "out", wait = 100.milliseconds) { ran = true }
            .assertError<FileError.LockBusy>()

        first.close()
        assertEquals(first.lockFile, busy.path)
        assertEquals(currentProcessId(), busy.holderPid)
        assertFalse(ran)
        assertEquals(Ok("ran"), dirs.awaitSingleInstance(out) { "ran" })
    }

    @Test
    fun `the block is handed the instance it holds so a long holder can check it`() = runTest {
        val checked = dirs.awaitSingleInstance("app") { instance -> instance.lockFile to instance.check() }

        assertEquals(Ok(dirs.state.realPath().assertSuccess() / ".app.lock" to Ok(Unit)), checked)
    }

    @Test
    fun `the pid file holds this process's pid while the block runs`() = runTest {
        val pid = dirs.awaitSingleInstance("app") { (dirs.state / "app.pid").readText() }

        assertEquals(Ok(Ok("${currentProcessId()}\n")), pid)
    }

    @Test
    fun `the instance is free again once the block returns or throws`() = runTest {
        dirs.awaitSingleInstance("app") { }.assertSuccess()
        dirs.singleInstance("app").assertSuccess().close()

        assertFailsWith<IllegalStateException> { dirs.awaitSingleInstance("app") { error("the work failed") } }

        dirs.singleInstance("app").assertSuccess().close()
    }

    @Test
    fun `a cancelled block releases the instance`() = runTest {
        val holder = launch { val _ = dirs.awaitSingleInstance("app") { awaitCancellation() } }
        yield()
        dirs.singleInstance("app").assertError<FileError.LockBusy>()

        holder.cancelAndJoin()

        dirs.singleInstance("app").assertSuccess().close()
    }

    @Test
    fun `a wait for a held instance is LockBusy having waited the sum of its pauses`() = runTest {
        dirs.singleInstance("app").assertSuccess().use {
            val busy = dirs.awaitSingleInstance("app", wait = 25.milliseconds) { }.assertError<FileError.LockBusy>()

            assertEquals(25.milliseconds, busy.waited)
        }
    }

    @Test
    fun `a cancelled wait holds nothing`() = runTest {
        val first = dirs.singleInstance("app").assertSuccess()
        val waiter = launch { val _ = dirs.awaitSingleInstance("app", wait = 10.seconds) { } }
        yield()

        waiter.cancelAndJoin()
        first.close()

        dirs.singleInstance("app").assertSuccess().close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a name that is not one name is InvalidName at once and runs nothing`() = runTest {
        var ran = false

        val refused = dirs.awaitSingleInstance("a/b") { ran = true }

        assertEquals(Err(FileError.InvalidName(dirs.state / ".a/b.lock", NameProblem.Separator)), refused)
        assertFalse(ran)
        assertEquals(0L, testScheduler.currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a state folder anybody may write is Refused at once and runs nothing`() = runTest {
        dirs.state.createDirectories().assertSuccess()
        dirs.state.chmod(0b111_111_111).assertSuccess()
        val info = dirs.state.info().assertSuccess()
        // Windows keeps no permissions, which leaves nothing to observe.
        if (info.permissions == null) return@runTest
        var ran = false

        val refused = dirs.awaitSingleInstance("app") { ran = true }

        val state = dirs.state.realPath().assertSuccess()
        assertEquals(Err(FileError.Refused(state, info, RefusedBecause.OthersMayWrite, dirs.state)), refused)
        assertFalse(ran)
        assertEquals(0L, testScheduler.currentTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a busy answer naming no holder takes the pid written while the pid file is read again on virtual time`() =
        runTest {
            val pidFile = dirs.state / "app.pid"
            pidFile.writeText("$NO_SUCH_PID\n").assertSuccess()
            launch {
                delay(25.milliseconds)
                pidFile.writeText("${currentProcessId()}\n").assertSuccess()
            }
            val busy = FileError.LockBusy(dirs.state / ".app.lock", holderPid = null, waited = Duration.ZERO)

            val named = dirs.namingHolder("app", busy)

            assertEquals(busy.copy(holderPid = currentProcessId()), named)
            assertEquals(30L, testScheduler.currentTime)
        }

    /** The waiting startup from fs's docs/run-temp.md, as written there. */
    private suspend fun runWhenFree(
        dirs: AppDirs,
        name: String,
        wait: Duration,
        work: (Writer) -> Result<Int, FileError>,
    ): Result<Int, FileError> = result {
        dirs.awaitSingleInstance(name, wait) { dirs.withRunTemp { temp -> work(temp).bind() }.bind() }.bind()
    }

    @Test
    fun `the waiting startup runs the work in this run's temp folder and passes its answer on`() = runTest {
        val answer = runWhenFree(dirs, "app", 1.seconds) { temp ->
            temp.writeText("scratch.txt", "x").map { 0 }
        }

        assertEquals(Ok(0), answer)
    }

    @Test
    fun `the waiting startup passes on the work's own error and a busy instance's`() = runTest {
        val missing = dirs.data / "missing.toml"

        val failed = runWhenFree(dirs, "app", 1.seconds) { missing.readText().map { 0 } }

        assertEquals(Err(FileError.NotFound(missing)), failed)
        dirs.singleInstance("app").assertSuccess().use {
            runWhenFree(dirs, "app", 25.milliseconds) { Ok(0) }.assertError<FileError.LockBusy>()
        }
    }

    private sealed interface AppError : ResultError {
        data class File(val error: FileError) : AppError

        data class BadJob(val reason: String) : AppError
    }

    /** The waiting startup for work answering the app's own error, from fs's docs/run-temp.md, as written there. */
    private suspend fun runAppWhenFree(
        dirs: AppDirs,
        name: String,
        wait: Duration,
        work: (Writer) -> Result<Int, AppError>,
    ): Result<Int, AppError> = result {
        dirs.awaitSingleInstance(name, wait) {
            dirs.withRunTemp { temp -> work(temp).bind() }.bind(AppError::File)
        }.bind(AppError::File)
    }

    @Test
    fun `the waiting startup for the app's own error passes on the work's error and a busy instance's as the app's`() =
        runTest {
            val failed = runAppWhenFree(dirs, "app", 1.seconds) { Err(AppError.BadJob("empty queue")) }

            assertEquals(Err(AppError.BadJob("empty queue")), failed)
            assertEquals(Ok(0), runAppWhenFree(dirs, "app", 1.seconds) { Ok(0) })
            dirs.singleInstance("app").assertSuccess().use {
                val busy = runAppWhenFree(dirs, "app", 25.milliseconds) { Ok(0) }.assertError<AppError.File>()
                assertTrue(busy.error is FileError.LockBusy, "$busy")
            }
        }

    /** The mirror run from the README, as written there, with [runMirror] and [println] handed in. */
    private suspend fun mirror(
        runMirror: suspend () -> Unit,
        println: (String) -> Unit,
    ) {
        dirs.awaitSingleInstance("mirror", wait = 30.seconds) { runMirror() }.onError { failure ->
            val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
            println("mirror still runs as pid $pid")
        }
    }

    @Test
    fun `the README's mirror runs when no other instance does and names the one that does`() = runTest {
        val printed = mutableListOf<String>()
        var runs = 0

        mirror(runMirror = { runs++ }, println = printed::add)
        dirs.singleInstance("mirror").assertSuccess().use { mirror(runMirror = { runs++ }, println = printed::add) }

        assertEquals(1, runs)
        assertEquals(listOf("mirror still runs as pid ${currentProcessId()}"), printed)
    }

    /** The long mirror from the README, as written there, with [mirrorOnce] and [println] handed in. */
    private suspend fun mirrorWhileHeld(
        mirrorOnce: () -> Unit,
        println: (String) -> Unit,
    ) {
        dirs.awaitSingleInstance("mirror") { instance ->
            while (instance.check() is Result.Success) mirrorOnce()
        }.onError { println("mirror not run: $it") }
    }

    @Test
    fun `the README's long mirror stops once its lock file is deleted`() = runTest {
        val printed = mutableListOf<String>()
        var runs = 0

        mirrorWhileHeld(
            mirrorOnce = {
                runs++
                check(runs <= 3) { "the mirror ran on after its lock file was deleted" }
                if (runs == 3) (dirs.state / ".mirror.lock").delete().assertSuccess()
            },
            println = printed::add,
        )

        assertEquals(3, runs)
        assertEquals(emptyList(), printed)
    }

    private companion object {
        /** Above every pid Linux and Apple hand out, and no multiple of four, as every Windows pid is. */
        const val NO_SUCH_PID = 99_999_999L
    }
}
