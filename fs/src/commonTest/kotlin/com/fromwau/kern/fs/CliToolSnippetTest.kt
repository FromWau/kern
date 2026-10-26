package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.IgnoredBecause
import com.fromwau.kern.dirs.IgnoredVariable
import com.fromwau.kern.dirs.toError
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The command-line tool from docs/apps.md, as written there, with the process's own calls stood in for. */
class CliToolSnippetTest {
    private val dir = newTempDir()
    private val base = BaseDirs.under(dir)
    private val config = dir / "config" / "tool"
    private val dotfiles = dir / "dotfiles"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private val errors = mutableListOf<String>()

    /** Stands in for kern:terminal's terminal, so a test can read what the tool writes to stderr. */
    private inner class ErrorTerminal {
        fun err(text: String) {
            errors += text
        }
    }

    private fun defaultTerminal() = ErrorTerminal()

    /** How [exitProcess] ends the tool here, with its [code]. */
    private class Exited(val code: Int) : Exception()

    /** Stands in for `kotlin.system.exitProcess`, which would end the test run. */
    private fun exitProcess(code: Int): Nothing = throw Exited(code)

    /** What the environment resolves to, set by each test. */
    private var environment: Result<BaseDirs, DirsError> = Ok(base)

    /** Stands in for the platform's `BaseDirs.current`, which reads this process's own environment. */
    private fun BaseDirs.Companion.current(): Result<BaseDirs, DirsError> = environment

    /** What the tool keeps as its session token, set by each test that does more while the tool runs. */
    private var sessionToken: () -> String = { "s3cret" }

    private fun newSessionToken(): String = sessionToken()

    /** The exit code `main` ended with. */
    internal fun exitOfMain(): Int = assertFailsWith<Exited> { main() }.code

    /** Runs the tool in the folders this process's environment gives, and exits with its code. */
    fun main() {
        exitProcess(runTool(BaseDirs.current()) { defaultTerminal().err("tool: $it\n") })
    }

    /** Runs the tool once in the folders [found] holds, writing each problem through [err], and answers its exit code. */
    fun runTool(
        found: Result<BaseDirs, DirsError>,
        err: (String) -> Unit,
    ): Int {
        val base = found.getOrElse { failure ->
            err("no folders: $failure")
            return 2
        }
        val refused = base.ignored.firstOrNull { it.name != "XDG_RUNTIME_DIR" }?.toError()
        if (refused != null) {
            err("$refused; fix the variable and run again")
            return 2
        }
        val dirs = base.forApp("tool").getOrElse { failure ->
            err("no folders: $failure")
            return 2
        }
        dirs.clearStaleTempRuns().onError { err("old runs not cleared: $it") }
        val done: EmptyResult<FileError> = result {
            dirs.singleInstance("tool").bind().use { dirs.withRunTemp { temp -> countRun(dirs, temp).bind() }.bind() }
        }
        done.onError { failure ->
            err("$failure")
            return exitCode(failure)
        }
        return 0
    }

    /** Counts this run in the config, notes it in the data folder and keeps a session token in state. */
    fun countRun(
        dirs: AppDirs,
        temp: Writer,
    ): EmptyResult<FileError> = result {
        val config = dirs.config / "tool.toml"
        val text = config.readText(maxBytes = 1_048_576, trusted = true).orIfNotFound { "runs = 0\n" }.bind()
        val runs = text.substringAfter("runs = ").substringBefore('\n').toIntOrNull() ?: 0
        writeConfig(dirs, config, "runs = ${runs + 1}\n").bind()
        temp.writeText("last-run.txt", "run ${runs + 1}\n").bind()
        dirs.data.writer().bind().copyFrom(temp.root / "last-run.txt", "last-run.txt").bind()
        dirs.state.writer().bind().privateWriter("secrets").bind().writeText("session", newSessionToken()).bind()
    }

    /**
     * Writes [text] to [config] for its owner alone. A backup a killed in-place write of it left is put back instead, and
     * the run ends as that [FileError.PendingBackup], since the text it read may be half of that write.
     */
    fun writeConfig(
        dirs: AppDirs,
        config: Path,
        text: String,
    ): EmptyResult<FileError> {
        val failure = config.writeText(text, ownerOnly = true).errorOrNull() ?: return Ok(Unit)
        if (failure !is FileError.PendingBackup) return Err(failure)
        val folder = dirs.config.writer(create = false).getOrElse { return Err(it) }
        folder.restore(failure.backup, throughLinks = true).onError { return Err(it) }
        return Err(failure)
    }

    /** The exit code for [failure]: a few by kind, the rest `1`. */
    fun exitCode(failure: FileError): Int = when (failure) {
        is FileError.LockBusy -> 3
        is FileError.Refused, is FileError.RunTempClaimed -> 4
        is FileError.WriteFailed -> if (failure.cause == IoCause.NoSpace) 5 else 1
        is FileError.PendingBackup -> 6
        else -> 1
    }

    @Test
    fun `the tool counts each run in its config and notes it in data and keeps its token in state`() {
        assertEquals(0, exitOfMain())
        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
        assertEquals("runs = 2\n", (config / "tool.toml").readRaw())
        config.assertPermissions(0b111_000_000)
        (config / "tool.toml").assertPermissions(0b110_000_000)
        assertEquals("run 2\n", (dir / "data" / "tool" / "last-run.txt").readRaw())
        assertEquals("s3cret", (dir / "state" / "tool" / "secrets" / "session").readRaw())
        (dir / "state" / "tool" / "secrets").assertPermissions(0b111_000_000)
        (dir / "state" / "tool" / "secrets" / "session").assertPermissions(0b110_000_000)
        (dir / "state" / "tool").assertPermissions(0b111_000_000)
    }

    @Test
    fun `the tool exits 2 on folders that could not be resolved`() {
        environment = Err(DirsError.NotAbsolute("HOME", "home"))

        assertEquals(2, exitOfMain())

        assertEquals(listOf("tool: no folders: ${DirsError.NotAbsolute("HOME", "home")}\n"), errors)
    }

    @Test
    fun `the tool exits 2 on a relative state variable and runs nothing`() {
        val state = IgnoredVariable("XDG_STATE_HOME", "state", IgnoredBecause.NotAbsolute)
        environment = Ok(base.copy(ignored = listOf(state)))

        assertEquals(2, exitOfMain())

        val refused = DirsError.NotAbsolute("XDG_STATE_HOME", "state")
        assertEquals(listOf("tool: $refused; fix the variable and run again\n"), errors)
        assertFalse(SystemFileSystem.exists(dir / "state"))
    }

    @Test
    fun `the tool runs with a runtime variable that names no private folder`() {
        val runtime = IgnoredVariable("XDG_RUNTIME_DIR", "/run/user/0", IgnoredBecause.NotPrivateFolder)
        environment = Ok(base.copy(ignored = listOf(runtime)))

        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a second run while the first holds the instance exits 3`() {
        var second = -1
        sessionToken = {
            second = exitOfMain()
            "s3cret"
        }

        assertEquals(0, exitOfMain())

        assertEquals(3, second)
        assertTrue(errors.single().startsWith("tool: LockBusy("), errors.toString())
    }

    @Test
    fun `a state folder others may write exits 4 and runs nothing`() {
        val state = dir / "state" / "tool"
        createDirectoriesRaw(state)
        state.chmod(0b111_111_111).assertSuccess()
        if (state.permissions().assertSuccess() == null) return

        assertEquals(4, exitOfMain())

        assertTrue(errors.single().startsWith("tool: Refused("), errors.toString())
        assertFalse(SystemFileSystem.exists(config / "tool.toml"))
    }

    @Test
    fun `a config linked into a dotfiles folder with a killed write's backup is put back and the run exits 6`() {
        createDirectoriesRaw(dotfiles)
        createDirectoriesRaw(config)
        val target = (dotfiles / "tool.toml").writeRaw("runs = 4")
        target.chmod(0b110_100_100).assertSuccess()
        val backup = (dotfiles / ".tool.toml.$A_WRITE_ID.bak").writeRaw("runs = 7\n")
        (config / "tool.toml").createSymlink(target).assertSuccess()

        assertEquals(6, exitOfMain())
        assertTrue(errors.single().startsWith("tool: PendingBackup("), errors.toString())
        assertEquals("runs = 7\n", target.readRaw())
        assertFalse(SystemFileSystem.exists(backup))
        errors.clear()

        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
        assertEquals("runs = 8\n", target.readRaw())
        assertEquals(Ok(target), (config / "tool.toml").readSymlink())
    }

    @Test
    fun `a claimed run temp exits 4 a full disk 5 and a pending backup 6`() {
        val path = dir / "x"
        val backup = Leftover.Backup(file = path, copy = dir / ".x.$A_WRITE_ID.bak", startedAt = null)

        assertEquals(4, exitCode(FileError.RunTempClaimed(path, holderPid = null)))
        assertEquals(5, exitCode(FileError.WriteFailed(path, IoCause.NoSpace, exception = null)))
        assertEquals(1, exitCode(FileError.WriteFailed(path, IoCause.Denied, exception = null)))
        assertEquals(6, exitCode(FileError.PendingBackup(path, backup)))
        assertEquals(1, exitCode(FileError.NotFound(Path("x"))))
    }
}
