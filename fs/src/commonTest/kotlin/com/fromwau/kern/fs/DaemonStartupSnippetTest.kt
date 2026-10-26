package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.test.LeftoverKind
import com.fromwau.kern.fs.test.leaveLeftover
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.onError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class DaemonStartupSnippetTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private val dirs = AppDirs(
        config = dir / "config",
        data = dir / "data",
        state = dir / "state",
        cache = dir / "cache",
        temp = dir / "tmp" / "daemon" / "2b1f6c3e-8d4a-4f0e-9a7b-5c3d2e1f0a9b",
    )

    init {
        createDirectoriesRaw(dirs.state, OWNER_ONLY_FOLDER)
    }

    private val reports = mutableListOf<FileError>()

    private fun report(failure: FileError) {
        reports += failure
    }

    private val printed = mutableListOf<String>()

    /** Stands in for the `println` the startup calls, so a test can read what it prints. */
    private fun println(message: String) {
        printed += message
    }

    /** The daemon startup from docs/leftovers.md, as written there. */
    private fun start(serve: () -> Unit) {
        fun Path.real(): Path = realPath().getOrElse { this }
        val mine = setOf((dirs.state / "db" / "index.json").real())
        val instance = dirs.singleInstance("daemon").getOrElse { return report(it) }
        instance.use {
            dirs.clearStaleTempRuns().fold(
                onSuccess = { cleanup -> cleanup.failed.forEach { report(it.error) } },
                onError = { report(it) },
            )
            val state = dirs.state.privateWriter().getOrElse { return report(it) }
            val scan = state.leftovers(depth = Int.MAX_VALUE).getOrElse { failure ->
                report(failure)
                LeftoverScan.EMPTY
            }
            scan.failed.forEach { report(it.error) }
            val oldest = scan.oldestBackups(throughLinks = true).values.filter { it.file.real() in mine }
            for (backup in oldest) state.restore(backup, throughLinks = true).onError { report(it) }
            (scan.throughLinks - oldest.toSet()).forEach { println("outside state, check it by hand: ${it.copy}") }
            // Only an in-place write leaves a Backup, so one beside a file outside `mine` is not the daemon's.
            for (leftover in scan.found - oldest.toSet()) {
                state.discard(leftover).onError { if (it !is FileError.NotFound) report(it) }
            }
            serve()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the daemon startup restores the oldest backup and discards every other leftover in state`() {
        createDirectoriesRaw(dirs.state / "db")
        val db = (dirs.state / "db" / "index.json").writeRaw("{")
        val older = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_000))
        val newer = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(2_000))
        (dirs.state / "db" / ".index.json.$older.bak").writeRaw("{\"a\": 1, \"b\": 2}")
        (dirs.state / "db" / ".index.json.$newer.bak").writeRaw("{\"a\": 1}")
        (dirs.state / "queue.json").leaveLeftover(LeftoverKind.Temp, content = "[")
        var served = false

        start { served = true }

        assertEquals(emptyList(), reports)
        assertTrue(served)
        assertEquals("{\"a\": 1, \"b\": 2}", db.readRaw())
        assertEquals(listOf(db), SystemFileSystem.list(dirs.state / "db"))
        assertEquals(
            setOf(dirs.state / ".daemon.lock", dirs.state / "daemon.pid", dirs.state / "db"),
            SystemFileSystem.list(dirs.state).toSet(),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the daemon startup restores through a link the oldest backup of its file outside state`() {
        val elsewhere = dir / "elsewhere"
        createDirectoriesRaw(elsewhere)
        createDirectoriesRaw(dirs.state / "db")
        val db = (elsewhere / "index.json").writeRaw("{")
        val older = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_000))
        val newer = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(2_000))
        (elsewhere / ".index.json.$older.bak").writeRaw("{\"a\": 1, \"b\": 2}")
        val later = (elsewhere / ".index.json.$newer.bak").writeRaw("{\"a\": 1}")
        links.make(dirs.state / "db" / "index.json", db)

        start {}

        assertEquals(emptyList(), reports)
        assertEquals(1, printed.count { it.startsWith("outside state, check it by hand: ") })
        assertTrue(printed.single().endsWith(later.name), printed.toString())
        assertEquals("{\"a\": 1, \"b\": 2}", db.readRaw())
        assertEquals(setOf(db, later), SystemFileSystem.list(elsewhere).toSet())
        assertEquals(Ok(true), (dirs.state / "db" / "index.json").isSymlink())
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the daemon startup names the backups of a file outside state that is not in mine and leaves them`() {
        val elsewhere = dir / "elsewhere"
        createDirectoriesRaw(elsewhere)
        val notes = (elsewhere / "notes.md").writeRaw("n")
        val _ = notes.leaveLeftover(LeftoverKind.Backup, content = "old")
        links.make(dirs.state / "notes.md", notes)

        start {}

        assertEquals(emptyList(), reports)
        assertEquals(1, printed.count { it.startsWith("outside state, check it by hand: ") })
        assertEquals("n", notes.readRaw())
        assertEquals(2, SystemFileSystem.list(elsewhere).size)
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the daemon startup restores the oldest backup of a file in mine that links to a file in state`() {
        createDirectoriesRaw(dirs.state / "db")
        createDirectoriesRaw(dirs.state / "data")
        val db = (dirs.state / "data" / "index.json").writeRaw("{")
        val older = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_000))
        val newer = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(2_000))
        (dirs.state / "data" / ".index.json.$older.bak").writeRaw("{\"a\": 1, \"b\": 2}")
        (dirs.state / "data" / ".index.json.$newer.bak").writeRaw("{\"a\": 1}")
        links.make(dirs.state / "db" / "index.json", db)

        start {}

        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), printed)
        assertEquals("{\"a\": 1, \"b\": 2}", db.readRaw())
        assertEquals(listOf(db), SystemFileSystem.list(dirs.state / "data"))
    }

    @Test
    fun `the daemon startup reports an oldest backup it cannot restore and keeps it`() {
        val db = dirs.state / "db" / "index.json"
        // A folder where the file belongs, so the backup cannot be put back.
        createDirectoriesRaw(db)
        val backup = db.leaveLeftover(LeftoverKind.Backup, content = "{")

        start {}

        assertEquals(1, reports.size)
        assertEquals("{", backup.readRaw())
    }

    @Test
    fun `the daemon startup discards a backup beside an append-only log and the log keeps its lines`() {
        createDirectoriesRaw(dirs.state)
        val log = (dirs.state / "events.log").writeRaw("one\ntwo\n")
        val _ = log.leaveLeftover(LeftoverKind.Backup, content = "one\n")

        start {}

        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), printed)
        assertEquals("one\ntwo\n", log.readRaw())
        assertEquals(
            setOf(dirs.state / ".daemon.lock", dirs.state / "daemon.pid", log),
            SystemFileSystem.list(dirs.state).toSet(),
        )
    }

    @Test
    fun `a Writer write of a file goes through once the daemon startup discarded its stray backup`() {
        createDirectoriesRaw(dirs.state)
        val queue = (dirs.state / "queue.json").writeRaw("[1]")
        val _ = queue.leaveLeftover(LeftoverKind.Backup, content = "[")

        start {}

        val state = dirs.state.privateWriter().assertSuccess()
        state.writeText("queue.json", "[2]").assertSuccess()
        assertEquals("[2]", queue.readRaw())
    }

    @Test
    fun `the daemon startup discards a killed stage in state with the temp inside it and reports nothing`() {
        createDirectoriesRaw(dirs.state)
        val stage = (dirs.state / "cache").leaveLeftover(LeftoverKind.StagedFolder)
        (stage / "z.txt").leaveLeftover(LeftoverKind.Temp, content = "z")

        start {}

        assertEquals(emptyList(), reports)
        assertEquals(
            setOf(dirs.state / ".daemon.lock", dirs.state / "daemon.pid"),
            SystemFileSystem.list(dirs.state).toSet(),
        )
    }
}
