package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InstanceLock
import com.fromwau.kern.fs.Reopen
import com.fromwau.kern.fs.Writer
import com.fromwau.kern.fs.appendLine
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.currentProcessId
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.discard
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.fileType
import com.fromwau.kern.fs.leftovers
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.privateWriter
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.fs.restore
import com.fromwau.kern.fs.singleInstance
import com.fromwau.kern.fs.test.LeftoverKind
import com.fromwau.kern.fs.test.leaveLeftover
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.fs.writer
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import com.fromwau.kern.result.result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path

/** The daemon from fs/docs/apps.md, as written there, with the process's own calls stood in for. */
class DaemonSnippetTest {
    private val dir = scratchDir()
    private val base = BaseDirs.under(dir)
    private val state = dir / "state" / "daemon"
    private val config = dir / "config" / "daemon" / "daemon.toml"
    private val marker = dir / "run" / "daemon" / "daemon.pid"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private val errors = mutableListOf<String>()

    /** Stands in for kern:terminal's terminal, so a test can read what the daemon writes to stderr. */
    private inner class ErrorTerminal {
        fun err(text: String) {
            errors += text
        }
    }

    private fun defaultTerminal() = ErrorTerminal()

    /** How [exitProcess] ends the daemon here, with its [code]. */
    private class Exited(val code: Int) : Exception()

    /** Stands in for `kotlin.system.exitProcess`, which would end the test run. */
    private fun exitProcess(code: Int): Nothing = throw Exited(code)

    /** Stands in for the platform's `BaseDirs.current`, which reads this process's own environment. */
    private fun BaseDirs.Companion.current(): Result<BaseDirs, DirsError> = Ok(base)

    /** Stands in for kotlinx-coroutines' `runBlocking`, which common code does not have. */
    private fun runBlocking(block: suspend CoroutineScope.() -> Int): Int {
        var code: Int? = null
        runTest { code = withContext(Dispatchers.Default) { block() } }
        return checkNotNull(code)
    }

    /** What the daemon does for each request it serves, set by each test. */
    private var serving: suspend (settings: StateFlow<String?>, db: Writer, log: AppendFile) -> Unit = { _, _, _ -> }

    /** How many requests come after the first, set by each test. */
    private var requestsAfterTheFirst = 0

    /** Stands in for your server: serves one request with [serving], answering whether another one comes. */
    private suspend fun serveNext(
        settings: StateFlow<String?>,
        db: Writer,
        log: AppendFile,
    ): Boolean {
        serving(settings, db, log)
        return requestsAfterTheFirst-- > 0
    }

    // The config watch waits for the real operating system's events, so each run goes on Dispatchers.Default.
    /** [runDaemon] in [base], as `main` runs it, answering its exit code. */
    private suspend fun daemon(): Int = withContext(Dispatchers.Default) { runDaemon(Ok(base)) { errors += it } }

    /** Waits for [settings] to hold [text]. */
    private suspend fun StateFlow<String?>.loaded(text: String) {
        val _ = withTimeout(10.seconds) { first { it == text } }
    }

    /** The code a daemon exits with that serves once with its config loaded. */
    internal suspend fun exitOfADaemon(): Int {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        return daemon()
    }

    /** The code a second daemon exits with while the first serves; the first must exit 0. */
    internal suspend fun exitOfASecondDaemon(): Int {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        var second = -1
        serving = { _, _, _ -> second = daemon() }
        assertEquals(0, daemon())
        return second
    }

    /** The code a daemon exits with whose lock file is removed after its first request, before its second. */
    internal suspend fun exitOfADaemonWhoseLockIsLost(): Int {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        requestsAfterTheFirst = 1
        var served = 0
        serving = { _, _, _ ->
            if (served++ == 0) (state / ".daemon.lock").delete().assertSuccess()
        }
        val code = daemon()
        assertEquals(1, served)
        return code
    }

    /** The code a daemon exits with whose `state` is a file. */
    internal suspend fun exitWithAFileAsState(): Int {
        state.writeText("not a folder").assertSuccess()
        return daemon()
    }

    /** The code the daemon exits with on folders that could not be resolved. */
    internal suspend fun exitWithoutFolders(): Int =
        runDaemon(Err(DirsError.NotAbsolute("HOME", "home"))) { errors += it }

    /** Runs the daemon in the folders this process's environment gives, and exits with its code. */
    fun main() {
        exitProcess(runBlocking { runDaemon(BaseDirs.current()) { defaultTerminal().err("daemon: $it\n") } })
    }

    /** Runs the daemon in the folders [found] holds until it is done, writing each problem through [err]. */
    suspend fun runDaemon(
        found: Result<BaseDirs, DirsError>,
        err: (String) -> Unit,
    ): Int {
        val dirs = found.flatMap { it.forApp("daemon") }.getOrElse { failure ->
            err("no folders: $failure")
            return 2
        }
        val served: EmptyResult<FileError> = result {
            dirs.singleInstance("daemon").bind().use { instance ->
                val state = dirs.state.writer().bind()
                recover(state, mine = setOf(dirs.state / "db" / "index.json"), err)
                val marker = dirs.runtime?.privateWriter()?.bind()
                marker?.writeText("daemon.pid", "${currentProcessId()}\n")?.bind()
                val db = state.privateWriter("db").bind()
                state.openAppend("daemon.log", Reopen.WhenMoved(), replaceLink = true).bind().use { log ->
                    serveWithConfig(instance, dirs.config / "daemon.toml", db, log, err).bind()
                }
                marker?.delete("daemon.pid")?.bind()
            }
        }
        served.onError { failure ->
            err("$failure")
            return when (failure) {
                is FileError.LockBusy -> 3
                is FileError.LockLost -> 4
                else -> 1
            }
        }
        return 0
    }

    /** Puts back what killed in-place writes of [mine] left in [state], and removes every other leftover there. */
    fun recover(
        state: Writer,
        mine: Set<Path>,
        err: (String) -> Unit,
    ) {
        val scan = state.leftovers(depth = Int.MAX_VALUE).getOrElse { failure ->
            err("leftovers not checked: $failure")
            return
        }
        scan.failed.forEach { err("not checked: ${it.error}") }
        val realMine = mine.map { it.real() }.toSet()
        val oldest = scan.oldestBackups().values.filter { it.file.real() in realMine }
        for (backup in oldest) state.restore(backup).onError { err("not restored: $it") }
        // Only an in-place write leaves a Backup, so one beside a file outside `mine` is not the daemon's.
        for (leftover in scan.found - oldest.toSet()) {
            state.discard(leftover).onError { if (it !is FileError.NotFound) err("not removed: $it") }
        }
    }

    /** This path with every link on the way resolved, or as it is when that fails. */
    fun Path.real(): Path = realPath().getOrElse { this }

    /**
     * Serves with the config at [config] loaded, loading it again each time its text changes, until [serveNext] answers
     * that no request is left or [instance] is lost.
     */
    suspend fun serveWithConfig(
        instance: InstanceLock,
        config: Path,
        db: Writer,
        log: AppendFile,
        err: (String) -> Unit,
    ): EmptyResult<FileError> = coroutineScope {
        val settings = MutableStateFlow<String?>(null)
        val watch = launch {
            config.textChanges(maxBytes = 1_048_576, trusted = true).collect { text ->
                text.onSuccess { settings.value = it }.onError { err("config not loaded: $it") }
            }
        }
        val served = result {
            while (serveNext(settings, db, log)) instance.check().bind()
        }
        watch.cancel()
        served
    }

    @Test
    fun `the daemon serves with its config loaded and loads it again when it changes`() = runTest {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        var pidWhileServing: Result<String, FileError>? = null
        var markerBitsWhileServing: Int? = null
        serving = { settings, db, log ->
            settings.loaded("port = 1\n")
            pidWhileServing = marker.readText(maxBytes = 64)
            markerBitsWhileServing = marker.permissions().assertSuccess()?.bits
            db.writeText("index.json", "{}").assertSuccess()
            log.appendLine("served").assertSuccess()
            config.writeText("port = 2\n", ownerOnly = true).assertSuccess()
            settings.loaded("port = 2\n")
        }

        assertEquals(0, daemon())

        assertEquals(emptyList(), errors)
        assertEquals(Ok("${currentProcessId()}\n"), pidWhileServing)
        markerBitsWhileServing?.let { assertEquals(0b110_000_000, it) }
        assertEquals(Err(FileError.NotFound(marker)), marker.fileType())
        assertEquals(Ok("served\n"), (state / "daemon.log").readText(maxBytes = 64))
        (state / "db").permissions().assertSuccess()?.let { assertEquals(0b111_000_000, it.bits) }
        (state / "db" / "index.json").permissions().assertSuccess()?.let { assertEquals(0b110_000_000, it.bits) }
    }

    @Test
    fun `the daemon puts back what a killed write left in state before it serves`() = runTest {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        (state / "db").createDirectories(ownerOnly = true).assertSuccess()
        val index = state / "db" / "index.json"
        index.writeText("{", ownerOnly = true).assertSuccess()
        val _ = index.leaveLeftover(LeftoverKind.Backup, content = "{\"a\": 1}")
        val _ = (state / "queue.json").leaveLeftover(LeftoverKind.Temp, content = "[")
        var seen: Result<String, FileError>? = null
        serving = { _, db, _ -> seen = db.readText("index.json") }

        assertEquals(0, daemon())

        assertEquals(emptyList(), errors)
        assertEquals(Ok("{\"a\": 1}"), seen)
        val left = state.writer().assertSuccess()
        assertEquals(listOf("db/index.json"), left.list("db").assertSuccess())
        assertEquals(listOf(".daemon.lock", "daemon.log", "daemon.pid", "db"), left.list().assertSuccess())
    }

    @Test
    fun `the daemon discards a backup beside a file it writes by rename and keeps that file`() = runTest {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()
        state.createDirectories(ownerOnly = true).assertSuccess()
        val settings = state / "settings.json"
        settings.writeText("new").assertSuccess()
        val _ = settings.leaveLeftover(LeftoverKind.Backup, content = "old")

        assertEquals(0, daemon())

        assertEquals(emptyList(), errors)
        assertEquals(Ok("new"), settings.readText(maxBytes = 64))
        val left = state.writer().assertSuccess().list().assertSuccess()
        assertEquals(listOf(".daemon.lock", "daemon.log", "daemon.pid", "db", "settings.json"), left)
    }

    @Test
    fun `a daemon whose lock file is removed while it serves stops and exits 4`() = runTest {
        assertEquals(4, exitOfADaemonWhoseLockIsLost())

        assertTrue(errors.single().startsWith("LockLost("), errors.toString())
    }

    @Test
    fun `a second daemon while the first serves exits 3`() = runTest {
        assertEquals(3, exitOfASecondDaemon())

        assertTrue(errors.single().startsWith("LockBusy("), errors.toString())
    }

    @Test
    fun `a daemon whose state is a file exits 1`() = runTest {
        assertEquals(1, exitWithAFileAsState())

        assertTrue(errors.single().startsWith("NotADirectory("), errors.toString())
    }

    @Test
    fun `the daemon exits 2 on folders that could not be resolved`() = runTest {
        assertEquals(2, exitWithoutFolders())

        assertEquals(listOf("no folders: ${DirsError.NotAbsolute("HOME", "home")}"), errors)
    }

    @Test
    fun `main exits with the daemon's code`() {
        config.writeText("port = 1\n", ownerOnly = true).assertSuccess()

        assertEquals(0, assertFailsWith<Exited> { main() }.code)
        assertEquals(emptyList(), errors)
    }
}
