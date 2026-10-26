package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.setModifiedAt
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import java.nio.file.Paths

/** The task snippets of the fs pages that no other test runs, each as written there. */
class DocsTasksJvmTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private val dirs = AppDirs(
        config = dir / "config",
        data = dir / "data",
        state = dir / "state",
        cache = dir / "cache",
        temp = dir / "tmp" / "app" / "2b1f6c3e-8d4a-4f0e-9a7b-5c3d2e1f0a9b",
    )

    private val reports = mutableListOf<FileError>()

    private fun report(failure: FileError) {
        reports += failure
    }

    private val printed = mutableListOf<String>()

    /** The value the last snippet run ended with, where its own code would go on to use it. */
    private var read: Any? = null

    private fun warn(failure: FileError) = report(failure)

    /** Stands in for the `println` the docs' examples call, so a test can read what they print. */
    private fun println(message: String) {
        printed += message
    }

    /** The folder built whole from docs/writer.md, as written there. */
    private fun install(
        data: Writer,
        version: String,
        manifest: String,
    ): EmptyResult<FileError> {
        val installed: EmptyResult<FileError> = result {
            data.createDirectoryAtomically("packages/$version") { stage ->
                stage.writeText("manifest.toml", manifest).bind()
            }.bind()
        }
        return installed
    }

    @Test
    fun `the writer's whole folder build places the version once and refuses it a second time`() {
        val data = dirs.data.writer().assertSuccess()

        assertEquals(Ok(Unit), install(data, "1.0", "name = \"tool\""))
        install(data, "1.0", "name = \"tool\"").assertError<FileError.AlreadyExists>()

        assertEquals("name = \"tool\"", (dirs.data / "packages" / "1.0" / "manifest.toml").readRaw())
        assertEquals(listOf(dirs.data / "packages" / "1.0"), SystemFileSystem.list(dirs.data / "packages"))
    }

    /** The secret from docs/writer.md, as written there, keeping what it read back in [read]. */
    private fun keepSecret(token: String) {
        val secrets = (dirs.state / "secrets").privateWriter().getOrElse { return warn(it) }
        secrets.writeText("token", token).onError { println("token not saved: $it") }
        val saved = secrets.readText("token").getOrElse { return warn(it) }
        read = saved
    }

    @Test
    fun `the writer's secret is written for the owner alone and read back`() {
        keepSecret("s3cret")

        assertEquals("s3cret", read)
        assertEquals(emptyList(), reports)

        assertEquals(emptyList(), printed)
        assertEquals(0b110_000_000, (dirs.state / "secrets" / "token").permissions().assertSuccess()?.bits)
    }

    /** The backup with its links as links from docs/walking.md, as written there. */
    private fun backUp(
        projects: Path,
        backupDisk: Path,
    ) {
        val backup = backupDisk.writer(create = false).getOrElse { return warn(it) }
        val report = projects.mirrorTo(backup).getOrElse { return warn(it) }
        report.passedOver.forEach { println("not backed up, it leads out: ${it.relative}") }
        report.failures.forEach { warn(it.error) }
        report.removalsDeclined
            .mapNotNull { it.why as? RemovalReason.AtPassedOver }
            .groupBy { it.place }
            .forEach { (place, kept) -> println("kept the old copy at $place: ${kept.size} entries") }
        read = report
    }

    /** The CI check from docs/walking.md's backup with links as links, as written there. */
    private fun ciPasses(report: MirrorReport): Boolean {
        val passed = report.unclean.all { unclean ->
            when (unclean) {
                is MirrorReport.Unclean.Kept -> unclean.removal.why is RemovalReason.AtPassedOver
                is MirrorReport.Unclean.LinkNotMade -> true
                is MirrorReport.Unclean.Held -> unclean.entry.why == WalkSkip.DanglingLink
                is MirrorReport.Unclean.Failed, is MirrorReport.Unclean.NotRemoved -> false
            }
        }
        return passed
    }

    @Test
    fun `the backup with links as links makes a link inside again and names one leading out`() {
        val projects = dir / "projects"
        createDirectoriesRaw(projects / "releases" / "43")
        (projects / "releases" / "43" / "app").writeRaw("app")
        (projects / "current").createSymlink(Path("releases/43")).assertSuccess()
        (projects / "shared").createSymlink(Path("../shared")).assertSuccess()
        val disk = dir / "disk"
        createDirectoriesRaw(disk)

        backUp(projects, disk)

        assertEquals(emptyList(), reports)
        assertEquals(listOf("not backed up, it leads out: shared"), printed)
        assertEquals(Ok(Path("releases/43")), (disk / "current").readSymlink())
        assertEquals("app", (disk / "releases" / "43" / "app").readRaw())
        assertFalse(SystemFileSystem.exists(disk / "shared"))
    }

    @Test
    fun `the backup with links as links keeps the copy of a folder that is now a link leading out`() {
        val projects = dir / "projects"
        createDirectoriesRaw(projects / "photos")
        (projects / "photos" / "a.jpg").writeRaw("a")
        (projects / "notes.md").writeRaw("n")
        (projects / "old.md").writeRaw("o")
        val disk = dir / "disk"
        createDirectoriesRaw(disk)
        backUp(projects, disk)
        SystemFileSystem.delete(projects / "photos" / "a.jpg")
        SystemFileSystem.delete(projects / "photos")
        SystemFileSystem.delete(projects / "old.md")
        (projects / "photos").createSymlink(Path("/nonexistent/share/photos")).assertSuccess()

        backUp(projects, disk)

        assertEquals(emptyList(), reports)
        assertEquals(
            listOf("not backed up, it leads out: photos", "kept the old copy at photos: 2 entries"),
            printed,
        )
        assertEquals("a", (disk / "photos" / "a.jpg").readRaw())
        assertFalse(SystemFileSystem.exists(disk / "old.md"))
        val report = read as MirrorReport
        assertFalse(report.isClean)
        assertTrue(ciPasses(report))
    }

    @Test
    fun `the backup CI check fails a run that kept a stale entry`() {
        val projects = dir / "projects"
        createDirectoriesRaw(projects)
        (projects / "notes.md").writeRaw("n")
        val disk = dir / "disk"
        createDirectoriesRaw(disk)
        (disk / "old.md").writeRaw("o")

        val report = projects.mirrorTo(disk.writer().assertSuccess(), approveRemovals = { emptyList() }).assertSuccess()

        assertFalse(ciPasses(report))
    }

    @Test
    fun `the backup CI check accepts a dangling link a mirror that follows links holds and fails a failed copy`() {
        val projects = dir / "projects"
        createDirectoriesRaw(projects)
        (projects / "notes.md").writeRaw("n")
        (projects / "gone.md").createSymlink(Path("missing.md")).assertSuccess()
        val disk = dir / "disk"
        val backup = disk.writer().assertSuccess()

        val dangling = projects.mirrorTo(backup, symlinks = Symlinks.Inside).assertSuccess()
        val failed = projects.mirrorTo(
            backup,
            symlinks = Symlinks.Inside,
            copy = { _, _, name -> Err(FileError.NotFound(disk / name)) },
            isCurrent = { _, _ -> false },
        ).assertSuccess()

        assertFalse(dangling.isClean)
        assertTrue(ciPasses(dangling))
        assertFalse(ciPasses(failed))
    }

    /** The run from docs/walking.md that clears an earlier copy at an AlreadyEntered link, as written there. */
    private fun clearAlreadyEntered(
        site: Path,
        out: Writer,
    ) {
        val report = site.mirrorTo(
            out,
            symlinks = Symlinks.Inside,
            approveRemovals = { plan ->
                val atTwins = plan.removals.filter { removal ->
                    val why = removal.why
                    why is RemovalReason.AtPassedOver && why.skip is WalkSkip.AlreadyEntered
                }
                if (plan.sourceEmpty) emptyList() else plan.defaultApproved() + atTwins
            },
        ).getOrElse { return warn(it) }
        read = report
    }

    @Test
    fun `the AlreadyEntered clearing run removes the copy at that link and keeps the one at a link leading out`() {
        val site = dir / "site"
        createDirectoriesRaw(site / "docs")
        (site / "docs" / "a.md").writeRaw("a")
        (site / "docs-v1").createSymlink(Path("docs")).assertSuccess()
        (site / "shared").createSymlink(Path("../elsewhere")).assertSuccess()
        val dst = dir / "dst"
        createDirectoriesRaw(dst / "docs-v1")
        (dst / "docs-v1" / "old.md").writeRaw("old")
        createDirectoriesRaw(dst / "shared")
        (dst / "shared" / "x.md").writeRaw("x")

        clearAlreadyEntered(site, dst.writer().assertSuccess())

        assertEquals(emptyList(), reports)
        assertEquals(listOf(Path("docs-v1", "old.md"), Path("docs-v1")), (read as MirrorReport).removed)
        assertFalse(SystemFileSystem.exists(dst / "docs-v1"))
        assertEquals("x", (dst / "shared" / "x.md").readRaw())
        assertEquals("a", (dst / "docs" / "a.md").readRaw())
    }

    @Test
    fun `the AlreadyEntered clearing run is clean once it removed the copy at that link`() {
        val site = dir / "site"
        createDirectoriesRaw(site / "docs")
        (site / "docs" / "a.md").writeRaw("a")
        (site / "docs-v1").createSymlink(Path("docs")).assertSuccess()
        val dst = dir / "dst"
        createDirectoriesRaw(dst / "docs-v1")
        (dst / "docs-v1" / "old.md").writeRaw("old")
        (dst / "stale.md").writeRaw("stale")

        clearAlreadyEntered(site, dst.writer().assertSuccess())

        val report = read as MirrorReport
        assertEquals(listOf(Path("docs-v1", "old.md"), Path("docs-v1"), Path("stale.md")), report.removed)
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `the AlreadyEntered clearing run removes nothing from a source holding only a link to itself`() {
        val site = dir / "site"
        createDirectoriesRaw(site)
        (site / "self").createSymlink(Path(".")).assertSuccess()
        val dst = dir / "dst"
        createDirectoriesRaw(dst / "self")
        (dst / "self" / "old.md").writeRaw("old")

        clearAlreadyEntered(site, dst.writer().assertSuccess())

        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), (read as MirrorReport).removed)
        assertEquals("old", (dst / "self" / "old.md").readRaw())
    }

    /** The read-only listing from docs/writer.md, as written there. */
    private fun listNotes() {
        val data = dirs.data.reader().getOrElse { return warn(it) }
        for (name in data.list("notes").getOrElse { return warn(it) }) println(name)
    }

    @Test
    fun `the writer's read-only listing prints each note and makes nothing for a missing folder`() {
        listNotes()
        assertEquals(listOf<FileError>(FileError.NotFound(dirs.data)), reports)
        assertFalse(SystemFileSystem.exists(dirs.data))

        createDirectoriesRaw(dirs.data / "notes")
        (dirs.data / "notes" / "a.md").writeRaw("a")
        listNotes()

        assertEquals(listOf("notes/a.md"), printed)
    }

    /** The missing file read from docs/reading.md, as written there. */
    private fun readSettings(settings: Path) {
        val text = settings.readText(maxBytes = 1_048_576).orIfNotFound { "" }.getOrElse { return report(it) }
        read = text
    }

    @Test
    fun `a missing settings file reads as empty`() {
        readSettings(dir / "settings.toml")
        assertEquals("", read)

        readSettings((dir / "settings.toml").writeRaw("a = 1"))
        assertEquals("a = 1", read)
        assertEquals(emptyList(), reports)
    }

    /** The trusted read from docs/reading.md, as written there. */
    private fun readTrusted(config: Path) {
        val text = config.readText(maxBytes = 1_048_576, trusted = true).getOrElse { return report(it) }
        read = text
    }

    @Test
    fun `a trusted read of a config only its owner may change reads it`() {
        val config = (dir / "app.toml").writeRaw("a = 1")
        config.chmod(0b110_100_100).assertSuccess()
        readTrusted(config)

        assertEquals("a = 1", read)
        assertEquals(emptyList(), reports)
    }

    /** The lookup from docs/reading.md, as written there. */
    private fun describe(path: Path) {
        val info = path.info().getOrElse { return report(it) }
        println("${info.type}, ${info.size} bytes, ${info.permissions}, written ${info.modifiedAt}")
    }

    @Test
    fun `the lookup prints the file's type and size`() {
        describe((dir / "a.txt").writeRaw("abc"))

        assertTrue(printed.single().startsWith("Regular, 3 bytes, "), printed.single())
    }

    /** The last line check from docs/appending.md, as written there. */
    private fun checkLastLine(log: AppendFile) {
        val unfinished = log.lastByte().getOrElse { return report(it) }.let { it != null && it != '\n'.code.toByte() }
        read = unfinished
    }

    @Test
    fun `the last line check tells an unfinished line from a finished one and an empty file`() {
        (dir / "app.log").openAppend().assertSuccess().use { log ->
            checkLastLine(log)
            assertEquals(false, read)

            log.append("started".encodeToByteArray()).assertSuccess()
            checkLastLine(log)
            assertEquals(true, read)

            log.appendLine("").assertSuccess()
            checkLastLine(log)
            assertEquals(false, read)
        }
    }

    /** The retry of a failed append from docs/appending.md, as written there. */
    private fun AppendFile.appendLineAgain(line: String): EmptyResult<FileError> {
        val torn = lastByte().getOrElse { return Err(it) }.let { it != null && it != '\n'.code.toByte() }
        if (torn) append("\n".encodeToByteArray()).onError { return Err(it) }
        return appendLine(line)
    }

    /** The call of [appendLineAgain] from docs/appending.md, as written there. */
    private fun appendLogged(
        log: AppendFile,
        line: String,
    ) {
        log.appendLine(line).onError { log.appendLineAgain(line).onError(::report) }
    }

    /** Appends half of the first bytes it is given and fails that append, as a full disk may. */
    private class TearsFirstAppend(private val file: AppendFile) : AppendFile by file {
        private var torn = false

        override fun append(bytes: ByteArray): EmptyResult<FileError> {
            if (torn) return file.append(bytes)
            torn = true
            file.append(bytes.copyOf(bytes.size / 2)).assertSuccess()
            return Err(FileError.WriteFailed(path, IoCause.NoSpace, exception = null))
        }
    }

    @Test
    fun `the retry of a failed append finishes the torn line before appending the line again`() {
        val log = dir / "app.log"
        log.openAppend().assertSuccess().use { it.appendLine("first").assertSuccess() }

        TearsFirstAppend(log.openAppend().assertSuccess()).use { appendLogged(it, "second line") }

        assertEquals(emptyList(), reports)
        assertEquals("first\nsecond\nsecond line\n", log.readRaw())
    }

    @Test
    fun `the retry of a failed append adds no line break after a finished line`() {
        val log = dir / "app.log"
        log.openAppend().assertSuccess().use { it.appendLine("first").assertSuccess() }

        log.openAppend().assertSuccess().use { it.appendLineAgain("second").assertSuccess() }

        assertEquals("first\nsecond\n", log.readRaw())
    }

    /** The private folders from docs/folders-and-moves.md, as written there. */
    private fun makeState() {
        dirs.state.createDirectories(ownerOnly = true).onError { return report(it) }
    }

    @Test
    fun `the private folders are made for the owner alone`() {
        makeState()

        assertEquals(emptyList(), reports)
        assertEquals(0b111_000_000, dirs.state.permissions().assertSuccess()?.bits)
    }

    /** The walk from docs/walking.md, as written there. */
    private fun listSizes(
        root: Path,
        backup: Path,
        warn: (FileError) -> Unit,
    ) {
        val entries = root.walkTopDown(exclude = setOf(backup)).getOrElse { return warn(it) }
        for (entry in entries) {
            entry.onError { warn(it.error) }
            val file = entry.getOrNull() as? WalkEntry.File ?: continue
            println("${file.relative}: ${file.size} bytes")
        }
    }

    @Test
    fun `the walk prints each file's size and leaves the backup out`() {
        val root = dir / "root"
        createDirectoriesRaw(root / "sub")
        createDirectoriesRaw(root / "backup")
        (root / "a.txt").writeRaw("ab")
        (root / "sub" / "b.txt").writeRaw("abc")
        (root / "backup" / "c.txt").writeRaw("c")

        listSizes(root, root / "backup", ::report)

        assertEquals(emptyList(), reports)
        assertEquals(listOf("a.txt: 2 bytes", "sub/b.txt: 3 bytes"), printed)
    }

    /** The single instance from docs/locking.md, as written there. */
    private fun startOnce(runApp: () -> Unit) {
        dirs.singleInstance("app").getOrElse { return report(it) }.use { runApp() }
    }

    @Test
    fun `the single instance runs the app once and keeps a second start out`() {
        var runs = 0

        startOnce { startOnce { runs++ } }

        assertEquals(0, runs)
        assertEquals(FileError.LockBusy::class, reports.single()::class)
        startOnce { runs++ }
        assertEquals(1, runs)
    }

    /** The pid marker from docs/locking.md, as written there. */
    private fun markSession(dirs: AppDirs) {
        dirs.runtime?.let { runtime ->
            val run = runtime.privateWriter().getOrElse { return report(it) }
            run.writeText("app.pid", "${currentProcessId()}\n").onError { report(it) }
        }
    }

    @Test
    fun `the pid marker holds this process's pid after a killed run's`() {
        val runtime = dir / "run" / "app"
        createDirectoriesRaw(runtime, OWNER_ONLY_FOLDER)
        (runtime / "app.pid").writeRaw("1\n")

        markSession(dirs.copy(runtime = runtime))
        markSession(dirs)

        assertEquals(emptyList(), reports)
        assertEquals("${currentProcessId()}\n", (runtime / "app.pid").readRaw())
        assertEquals(listOf(runtime / "app.pid"), SystemFileSystem.list(runtime))
    }

    /** The shared lock with the run temp from docs/run-temp.md, as written there. */
    private fun thumbnails(buildThumbnails: (Writer) -> Thumbnails): Result<Thumbnails, FileError> {
        val thumbnails: Result<Thumbnails, FileError> = result {
            (dirs.cache / ".thumbnails.lock")
                .withLock { dirs.withRunTemp { temp -> buildThumbnails(temp) }.bind() }
                .bind()
        }
        return thumbnails
    }

    @Test
    fun `the shared lock and the run temp answer the build's value`() {
        assertEquals(Ok(3), thumbnails { temp -> temp.writeText("t.png", "png").getOrNull()?.let { 3 } ?: 0 })
    }

    /** The opening example of docs/writer.md, as written there. */
    private fun saveNote(text: String) {
        val data = dirs.data.writer().getOrElse { return warn(it) }
        data.writeText("notes/today.md", text).onError { println("not saved: $it") }
    }

    @Test
    fun `the writer's opening example saves the note and the folder on the way`() {
        saveNote("buy milk")

        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), printed)
        assertEquals("buy milk", (dirs.data / "notes" / "today.md").readRaw())
    }

    /** The write from docs/writer.md, as written there. */
    private fun saveIndex(
        data: Writer,
        json: String,
    ) {
        data.writeText("cache/index.json", json).onError { println("index not saved: $it") }
    }

    @Test
    fun `the writer's write makes the folder on the way`() {
        saveIndex(dirs.data.writer().assertSuccess(), "{}")

        assertEquals(emptyList(), printed)
        assertEquals("{}", (dirs.data / "cache" / "index.json").readRaw())
    }

    /** The read from docs/writer.md, as written there, keeping what it read in [read]. */
    private fun readConfig(data: Writer) {
        val config = data.readText("app.toml", maxBytes = 1_048_576).orIfNotFound { "" }.getOrElse { return warn(it) }
        read = config
    }

    @Test
    fun `the writer's read answers a missing config as empty and refuses one over a megabyte`() {
        val data = dirs.data.writer().assertSuccess()

        readConfig(data)
        assertEquals("", read)

        (dirs.data / "app.toml").writeRaw("a = 1")
        readConfig(data)
        assertEquals("a = 1", read)

        (dirs.data / "app.toml").writeRaw("a".repeat(1_048_577))
        readConfig(data)
        assertEquals(FileError.TooLarge::class, reports.single()::class)
    }

    /** The import from docs/writer.md, as written there. */
    private fun import(
        data: Writer,
        picked: Path,
    ) {
        data.copyFrom(picked, "imports/${picked.name}", keepAttributes = true).onError { println("not imported: $it") }
    }

    @Test
    fun `the writer's import copies the picked file and keeps its modification time`() {
        val picked = (dir / "photo.jpg").writeRaw("jpg")

        import(dirs.data.writer().assertSuccess(), picked)

        assertEquals(emptyList(), printed)
        val copy = dirs.data / "imports" / "photo.jpg"
        assertEquals("jpg", copy.readRaw())
        assertEquals(picked.info().assertSuccess().modifiedAt, copy.info().assertSuccess().modifiedAt)
    }

    /** The log append from docs/writer.md, as written there. */
    private fun logStart() {
        val state = dirs.state.writer().getOrElse { return warn(it) }
        val log = state.openAppend("app.log", Reopen.WhenMoved()).getOrElse { return warn(it) }
        log.use { it.appendLine("started") }
    }

    @Test
    fun `the writer's log append adds a line each start`() {
        logStart()
        logStart()

        assertEquals(emptyList(), reports)
        assertEquals("started\nstarted\n", (dirs.state / "app.log").readRaw())
    }

    private var syncs = 0

    /** Stands in for the work the docs' lock examples hold the lock around. */
    private fun syncNow() {
        syncs++
    }

    /** The lock from docs/writer.md, as written there. */
    private fun syncLocked() {
        val state = dirs.state.writer().getOrElse { return warn(it) }
        state.withLock(".sync.lock") { syncNow() }.onError { println("not synced: $it") }
    }

    @Test
    fun `the writer's lock runs the sync and leaves the lock file`() {
        syncLocked()

        assertEquals(1, syncs)
        assertEquals(emptyList(), printed)
        assertEquals(Ok(FileType.Regular), (dirs.state / ".sync.lock").fileType())
    }

    /** The release switch from docs/writer.md, as written there. */
    private fun switchRelease(data: Writer) {
        data.switchSymlink("current", Path("releases/43")).onError { println("still on the old release: $it") }
    }

    @Test
    fun `the writer's release switch points the link at the new release`() {
        val data = dirs.data.writer().assertSuccess()
        data.switchSymlink("current", Path("releases/42")).assertSuccess()

        switchRelease(data)

        assertEquals(emptyList(), printed)
        assertEquals(Ok(Path("releases/43")), (dirs.data / "current").readSymlink())
    }

    /** The child writer from docs/writer.md, as written there, keeping it in [read]. */
    private fun pluginWriter(
        data: Writer,
        id: String,
    ) {
        val plugin = data.writer("plugins/$id").getOrElse { return warn(it) }
        read = plugin
    }

    @Test
    fun `the writer's child writer has the plugin folder as its root`() {
        pluginWriter(dirs.data.writer().assertSuccess(), "spell")

        assertEquals(emptyList(), reports)
        assertEquals((dirs.data / "plugins" / "spell").realPath().assertSuccess(), (read as Writer).root)
    }

    /** The folder that is a link to another place from docs/writer.md, as written there. */
    private fun useLinkedFolder(
        notes: Writer,
        text: String,
    ) {
        val work = (notes.root / "work").writer(create = false).getOrElse { return warn(it) }
        work.writeText("todo.md", text).onError { warn(it) }
        val scan = work.leftovers().getOrElse { return warn(it) }
        for (backup in scan.oldestBackups().values) work.restore(backup).onError { warn(it) }
    }

    @Test
    fun `a folder that is a link out gets a writer of its own that writes scans and restores there`() {
        val notes = (dir / "notes").also { createDirectoriesRaw(it) }.writer().assertSuccess()
        val work = (dir / "dotfiles" / "work").also { createDirectoriesRaw(it) }
        (work / "plan.md").writeRaw("half")
        (work / ".plan.md.$A_WRITE_ID.bak").writeRaw("whole")
        (notes.root / "work").createSymlink(Path("../dotfiles/work")).assertSuccess()
        val throughNotes = notes.writeText("work/todo.md", "x").assertError<FileError.Escapes>()

        useLinkedFolder(notes, "buy milk")

        assertEquals(notes.root / "work" / "todo.md", throughNotes.path)
        assertEquals(listOf(notes.root / "work"), notes.leftovers(depth = 1).assertSuccess().passedOver)
        assertEquals(emptyList(), reports)
        assertEquals(mapOf("plan.md" to "whole", "todo.md" to "buy milk"), work.tree())
    }

    @Test
    fun `a folder that is a dangling link out gets no writer and nothing is made`() {
        val notes = (dir / "notes").also { createDirectoriesRaw(it) }.writer().assertSuccess()
        (notes.root / "work").createSymlink(Path("../dotfiles/work")).assertSuccess()

        useLinkedFolder(notes, "buy milk")

        assertEquals(listOf<FileError>(FileError.NotFound(notes.root / "work")), reports)
        assertFalse(SystemFileSystem.exists(dir / "dotfiles"))
    }

    /** The config kept in a dotfiles repo from docs/writer.md, as written there. */
    private fun saveLinkedConfig(
        name: String,
        text: String,
    ) {
        val config = dirs.config.writer().getOrElse { return warn(it) }
        config.writeText(name, text).onError { refused ->
            val linked = refused is FileError.Escapes ||
                refused is FileError.NotRegularFile && refused.type == FileType.Symlink
            if (!linked) return warn(refused)
            val path = config.root / name
            val leadsTo = path.realPath().getOrElse { missing ->
                // Nothing there yet: follow the links at the name, at most 40 as Linux does, to the first that is none.
                var placed = path
                var followed = 0
                while (followed++ < 40) {
                    val target = placed.readSymlink().getOrNull() ?: break
                    placed = if (target.isAbsolute) target else (placed.parent ?: Path(".")) / target
                }
                if (placed.readSymlink().getOrNull() != null) return warn(missing)
                val folder = (placed.parent ?: placed).realPath().getOrElse { return warn(it) }
                folder / placed.name
            }
            val repo = dotfiles.realPath().getOrElse { return warn(it) }
            if (leadsTo.relativeTo(repo) == null) return warn(FileError.Escapes(path, repo, leadsTo))
            path.writeText(text, WriteMode.Replace).onError { return warn(it) }
        }
    }

    @Test
    fun `the config kept in a dotfiles repo is written through its link and the link stays`() {
        createDirectoriesRaw(dotfiles)
        createDirectoriesRaw(dirs.config)
        (dotfiles / "app.toml").writeRaw("old")
        (dirs.config / "app.toml").createSymlink(dotfiles / "app.toml").assertSuccess()

        saveLinkedConfig("app.toml", "new")

        assertEquals(emptyList(), reports)
        assertEquals("new", (dotfiles / "app.toml").readRaw())
        assertEquals(Ok(dotfiles / "app.toml"), (dirs.config / "app.toml").readSymlink())
    }

    @Test
    fun `the config kept in a dotfiles repo is written through a folder linked out`() {
        createDirectoriesRaw(dotfiles / "nvim")
        createDirectoriesRaw(dirs.config)
        (dirs.config / "nvim").createSymlink(dotfiles / "nvim").assertSuccess()
        dirs.config.writer().assertSuccess().writeText("nvim/init.lua", "x").assertError<FileError.Escapes>()

        saveLinkedConfig("nvim/init.lua", "new")

        assertEquals(emptyList(), reports)
        assertEquals("new", (dotfiles / "nvim" / "init.lua").readRaw())
        assertEquals(Ok(dotfiles / "nvim"), (dirs.config / "nvim").readSymlink())
    }

    @Test
    fun `the config kept in a dotfiles repo is not written through a link leading out of the repo`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        createDirectoriesRaw(dotfiles)
        createDirectoriesRaw(dirs.config)
        (outside / "app.toml").writeRaw("theirs")
        (dirs.config / "app.toml").createSymlink(outside / "app.toml").assertSuccess()

        saveLinkedConfig("app.toml", "new")

        val path = dirs.config.writer().assertSuccess().root / "app.toml"
        val resolved = outside.realPath().assertSuccess() / "app.toml"
        val expected = FileError.Escapes(path, dotfiles.realPath().assertSuccess(), resolved)
        assertEquals(listOf<FileError>(expected), reports)
        assertEquals("theirs", (outside / "app.toml").readRaw())
    }

    @Test
    fun `the config kept in a dotfiles repo is not written through a dangling link leading out of the repo`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        createDirectoriesRaw(dotfiles)
        createDirectoriesRaw(dirs.config)
        (dirs.config / "app.toml").createSymlink(outside / "app.toml").assertSuccess()

        saveLinkedConfig("app.toml", "new")

        assertTrue(reports.single() is FileError.Escapes, "$reports")
        assertEquals(emptyList(), SystemFileSystem.list(outside))
    }

    @Test
    fun `the config kept in a dotfiles repo is not written through a dangling chain that leaves the repo`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        createDirectoriesRaw(dotfiles)
        createDirectoriesRaw(dirs.config)
        (dotfiles / "app.toml").createSymlink(outside / "app.toml").assertSuccess()
        (dirs.config / "app.toml").createSymlink(dotfiles / "app.toml").assertSuccess()

        saveLinkedConfig("app.toml", "new")

        assertTrue(reports.single() is FileError.Escapes, "$reports")
        assertEquals(emptyList(), SystemFileSystem.list(outside))
    }

    @Test
    fun `the config kept in a dotfiles repo reports what is not a link`() {
        createDirectoriesRaw(dirs.config / "app.toml")

        saveLinkedConfig("app.toml", "new")

        val folder = dirs.config.realPath().assertSuccess() / "app.toml"
        assertEquals(listOf<FileError>(FileError.NotRegularFile(folder, FileType.Directory)), reports)
    }

    /** The leftover recovery from docs/writer.md, as written there. */
    private fun recover(data: Writer) {
        val scan = data.leftovers(depth = Int.MAX_VALUE).getOrElse { return warn(it) }
        val oldest = scan.oldestBackups(throughLinks = true)
        for ((file, leftovers) in scan.byFile(throughLinks = true)) {
            val backup = oldest[file]
            if (backup != null) {
                data.restore(backup, throughLinks = true).onError {
                    warn(it)
                    continue
                }
            }
            for (leftover in leftovers.filter { it != backup }) {
                data.discard(leftover, throughLinks = true).onError { warn(it) }
            }
        }
    }

    @Test
    fun `the writer's recovery restores a config linked into a dotfiles folder beside the link's target`() {
        val data = dirs.data.writer().assertSuccess()
        createDirectoriesRaw(dotfiles, mode = 0b111_101_101)
        (dotfiles / "app.toml").writeRaw("half")
        (dotfiles / ".app.toml.$A_WRITE_ID.bak").writeRaw("whole")
        (dotfiles / ".app.toml.$laterWriteId.tmp").writeRaw("new")
        (dirs.data / "app.toml").createSymlink(dotfiles / "app.toml").assertSuccess()

        recover(data)

        assertEquals(emptyList(), reports)
        assertEquals(mapOf("app.toml" to "whole"), dotfiles.tree())
        assertEquals(mapOf("app.toml" to "-> ${dotfiles / "app.toml"}"), dirs.data.tree())
    }

    @Test
    fun `the writer's recovery restores each oldest backup and keeps the copies of a file it could not restore`() {
        val data = dirs.data.writer().assertSuccess()
        val later = "0192d3a4-5b6d-7d8e-9f01-23456789abcd"
        (dirs.data / "a.toml").writeRaw("half")
        (dirs.data / ".a.toml.$A_WRITE_ID.bak").writeRaw("whole")
        (dirs.data / ".a.toml.$later.bak").writeRaw("later")
        (dirs.data / ".a.toml.$later.tmp").writeRaw("new")
        val theirs = (dir / "theirs.toml").writeRaw("theirs")
        (dirs.data / "b.toml").createSymlink(theirs).assertSuccess()
        (dirs.data / ".b.toml.$A_WRITE_ID.bak").writeRaw("b")
        (dirs.data / ".b.toml.$later.tmp").writeRaw("b new")

        recover(data)

        assertEquals(FileError.NotRegularFile::class, reports.single()::class)
        assertEquals(
            mapOf(
                ".b.toml.$A_WRITE_ID.bak" to "b",
                ".b.toml.$later.tmp" to "b new",
                "a.toml" to "whole",
                "b.toml" to "-> $theirs",
            ),
            dirs.data.tree(),
        )
    }

    /** The restore of what may be damaged and discard of the rest from docs/leftovers.md, as written there. */
    private fun restoreDamagedDiscardRest(confirm: (String) -> Boolean) {
        val notes = (dirs.data / "notes").writer().getOrElse { return report(it) }
        val scan = notes.leftovers().getOrElse { return report(it) }
        val byFile = scan.byFile(throughLinks = true)
        for ((file, oldest) in scan.oldestBackups(throughLinks = true)) {
            if (!confirm("restore $file?")) continue
            notes.restore(oldest, throughLinks = true).onError {
                report(it)
                continue
            }
            val later = byFile.getValue(file).filterIsInstance<Leftover.Backup>().drop(1)
            for (backup in later) notes.discard(backup, throughLinks = true).onError { report(it) }
        }
        for (leftover in (scan.found + scan.throughLinks).filterNot { it.fileMayBeDamaged }) {
            notes.discard(leftover, throughLinks = true).onError { report(it) }
        }
    }

    private val notes = dirs.data / "notes"
    private val dotfiles = dir / "dotfiles"
    private val laterWriteId = "0192d3a4-5b6d-7d8e-9f01-23456789abcd"

    /** Notes with a half-written `a.md` backed up twice, and a `d.md` that is a link into a dotfiles folder. */
    private fun damagedNotes() {
        createDirectoriesRaw(notes, mode = 0b111_101_101)
        createDirectoriesRaw(dotfiles, mode = 0b111_101_101)
        (notes / "a.md").writeRaw("half")
        (notes / ".a.md.$A_WRITE_ID.bak").writeRaw("whole")
        (notes / ".a.md.$laterWriteId.bak").writeRaw("later")
        (notes / "b.md").writeRaw("b")
        (notes / ".b.md.$A_WRITE_ID.tmp").writeRaw("b new")
        (notes / ".c.md.$A_WRITE_ID.bak.part").writeRaw("c")
        (dotfiles / "d.md").writeRaw("d half")
        (dotfiles / ".d.md.$A_WRITE_ID.bak").writeRaw("d whole")
        (dotfiles / ".d.md.$laterWriteId.bak").writeRaw("d later")
        (dotfiles / ".d.md.$laterWriteId.tmp").writeRaw("d new")
        (notes / "d.md").createSymlink(dotfiles / "d.md").assertSuccess()
    }

    @Test
    fun `the damaged files recipe restores each file asked for and leaves no leftover to stop a write`() {
        damagedNotes()
        val asked = mutableListOf<String>()

        restoreDamagedDiscardRest { question -> true.also { asked += question } }

        assertEquals(emptyList(), reports)
        assertEquals(
            setOf(
                "restore ${notes.realPath().assertSuccess() / "a.md"}?",
                "restore ${dotfiles.realPath().assertSuccess() / "d.md"}?",
            ),
            asked.toSet(),
        )
        assertEquals("whole", (notes / "a.md").readRaw())
        notes.writer().assertSuccess().writeText("a.md", "new").assertSuccess()
        assertEquals(mapOf("a.md" to "new", "b.md" to "b", "d.md" to "-> ${dotfiles / "d.md"}"), notes.tree())
        assertEquals(mapOf("d.md" to "d whole"), dotfiles.tree())
    }

    @Test
    fun `the damaged files recipe keeps every backup of a file whose restore is declined`() {
        damagedNotes()

        restoreDamagedDiscardRest { false }

        assertEquals(emptyList(), reports)
        assertEquals(
            mapOf(
                ".a.md.$A_WRITE_ID.bak" to "whole",
                ".a.md.$laterWriteId.bak" to "later",
                "a.md" to "half",
                "b.md" to "b",
                "d.md" to "-> ${dotfiles / "d.md"}",
            ),
            notes.tree(),
        )
        assertEquals(
            mapOf(
                ".d.md.$A_WRITE_ID.bak" to "d whole",
                ".d.md.$laterWriteId.bak" to "d later",
                "d.md" to "d half",
            ),
            dotfiles.tree(),
        )
    }

    /** The package cache scan from docs/leftovers.md, as written there. */
    private fun discardCutShortBuilds() {
        val packages = (dirs.cache / "packages").writer().getOrElse { return report(it) }
        val locks = (dirs.cache / "locks").writer().getOrElse { return report(it) }
        val scan = packages.leftovers(depth = 1).getOrElse { return report(it) }
        for (staged in scan.found) {
            val discarded: EmptyResult<FileError> = when (staged) {
                is Leftover.StagedFolder -> packages.discard(staged)
                is Leftover.StagedLink -> result {
                    locks.withLock("${staged.file.parent?.name}/current.lock") { packages.discard(staged).bind() }.bind()
                }
                else -> continue
            }
            discarded.onError { if (it !is FileError.LockBusy) report(it) }
        }
    }

    private val packages = dirs.cache / "packages"
    private val stagedCurrent = packages / "tool" / ".current.$A_WRITE_ID.link"

    /** A package `tool` with `1.0` installed, `current` leading to it, and a killed switch to `2.0` beside it. */
    private fun cutShortSwitch() {
        createDirectoriesRaw(packages / "tool" / "1.0", mode = 0b111_101_101)
        (packages / "tool" / "current").createSymlink(Path("1.0")).assertSuccess()
        stagedCurrent.createSymlink(Path("2.0")).assertSuccess()
    }

    @Test
    fun `the package cache scan discards a stage beside the versions and reads no installed file`() {
        createDirectoriesRaw(packages / "tool" / "1.0", mode = 0b111_101_101)
        (packages / "tool" / "1.0" / ".a.md.$A_WRITE_ID.tmp").writeRaw("installed")
        createDirectoriesRaw(packages / "tool" / ".2.0.$A_WRITE_ID.dir", mode = 0b111_101_101)
        (packages / "tool" / ".2.0.$A_WRITE_ID.dir" / "manifest.toml").writeRaw("half")

        discardCutShortBuilds()

        assertEquals(emptyList(), reports)
        assertEquals(
            mapOf("tool" to "dir", "tool/1.0" to "dir", "tool/1.0/.a.md.$A_WRITE_ID.tmp" to "installed"),
            packages.tree(),
        )
    }

    @Test
    fun `the package cache scan discards the staged link a killed switch of current left`() {
        cutShortSwitch()

        discardCutShortBuilds()

        assertEquals(emptyList(), reports)
        assertEquals(mapOf("tool" to "dir", "tool/1.0" to "dir", "tool/current" to "-> 1.0"), packages.tree())
    }

    @Test
    fun `the package cache scan keeps the staged link of a switch that holds the lock`() {
        cutShortSwitch()
        val locks = (dirs.cache / "locks").writer().assertSuccess()

        locks.tryLock("tool/current.lock").assertSuccess().use { discardCutShortBuilds() }

        assertEquals(emptyList(), reports)
        assertEquals(Ok(Path("2.0")), stagedCurrent.readSymlink())
    }

    /** A file's size and modification time, as a run saw them. */
    data class Stamp(val size: Long, val modifiedAt: Instant)

    /** The record docs/walking.md's stick backup loads before a run: what the run before it saved. */
    private var lastRun: Map<Path, Pair<Stamp, Stamp>> = emptyMap()

    /**
     * The backup to a stick that keeps coarse times from docs/walking.md, as written there, keeping its report and
     * saving its record as the next run's [lastRun].
     */
    private fun backUpToStick(
        photos: Path,
        stickFolder: Path,
    ) {
        val stick = stickFolder.writer(create = false).getOrElse { return warn(it) }
        val copies = mutableMapOf<Path, Pair<Stamp, Stamp>>()
        val report = photos.mirrorTo(
            stick,
            isCurrent = { source, existing ->
                val stamps = Stamp(source.size, source.modifiedAt) to Stamp(existing.size, existing.modifiedAt)
                val current = lastRun[source.relative] == stamps
                if (current) copies[source.relative] = stamps
                current
            },
            copy = { source, out, name ->
                out.copyFrom(source.path, name, modifiedAt = source.modifiedAt).map { written ->
                    val copy = written.info
                    copies[source.relative] = Stamp(source.size, source.modifiedAt) to Stamp(copy.size, copy.modifiedAt)
                }
            },
        ).getOrElse { return warn(it) }
        val leftAlone = report.held.map { it.relative } + report.failures.map { it.relative } +
            report.removalsDeclined.map { it.path }
        for ((relative, stamps) in lastRun) {
            if (generateSequence(relative) { it.parent }.any { it in leftAlone }) copies[relative] = stamps
        }
        read = report
        lastRun = copies
    }

    private val photos = dir / "photos"
    private val stick = dir / "stick"

    @Test
    fun `the stick backup leaves a copy current whose time the stick rounded`() {
        createDirectoriesRaw(photos, mode = 0b111_101_101)
        createDirectoriesRaw(stick, mode = 0b111_101_101)
        val taken = Instant.parse("2001-02-03T04:05:06.789Z")
        val rounded = Instant.parse("2001-02-03T04:05:06Z")
        (photos / "a.jpg").writeRaw("jpg").setModifiedAt(taken).assertSuccess()
        (stick / "a.jpg").writeRaw("jpg").setModifiedAt(rounded).assertSuccess()
        lastRun = mapOf(Path("a.jpg") to (Stamp(3, taken) to Stamp(3, rounded)))

        backUpToStick(photos, stick)

        assertEquals(emptyList(), reports)
        val report = read as MirrorReport
        assertEquals(emptyList(), report.copied)
        assertEquals(listOf(Mirrored(Path("a.jpg"), Path("a.jpg"))), report.unchanged)
    }

    @Test
    fun `the stick backup gives a copy its source's modification time`() {
        createDirectoriesRaw(photos, mode = 0b111_101_101)
        createDirectoriesRaw(stick, mode = 0b111_101_101)
        val taken = Instant.parse("2001-02-03T04:05:06Z")
        (photos / "a.jpg").writeRaw("jpg").setModifiedAt(taken).assertSuccess()

        backUpToStick(photos, stick)

        assertEquals(emptyList(), reports)
        assertEquals(taken, (stick / "a.jpg").info().assertSuccess().modifiedAt)
    }

    @Test
    fun `the stick backup copies a file again once its source is written or its copy changed`() {
        createDirectoriesRaw(photos, mode = 0b111_101_101)
        createDirectoriesRaw(stick, mode = 0b111_101_101)
        (photos / "a.jpg").writeRaw("aaa")
        (photos / "b.jpg").writeRaw("bbb")
        backUpToStick(photos, stick)
        backUpToStick(photos, stick)
        assertEquals(2, (read as MirrorReport).unchanged.size)

        (photos / "a.jpg").setModifiedAt(Instant.parse("2001-02-03T04:05:06Z")).assertSuccess()
        (stick / "b.jpg").writeRaw("xxx").setModifiedAt(Instant.parse("2001-02-03T04:05:06Z")).assertSuccess()
        backUpToStick(photos, stick)

        assertEquals(emptyList(), reports)
        val report = read as MirrorReport
        assertEquals(listOf(Path("a.jpg"), Path("b.jpg")), report.copied.map { it.relative })
        assertEquals(mapOf("a.jpg" to "aaa", "b.jpg" to "bbb"), stick.tree())
    }

    @Test
    fun `the stick backup carries over the record of a folder it could not read and drops a file gone`() {
        val album = photos / "album"
        createDirectoriesRaw(album, mode = 0b111_101_101)
        createDirectoriesRaw(stick, mode = 0b111_101_101)
        (album / "a.jpg").writeRaw("aaa")
        (photos / "b.jpg").writeRaw("bbb")
        backUpToStick(photos, stick)
        val recorded = lastRun.getValue(Path("album", "a.jpg"))
        SystemFileSystem.delete(photos / "b.jpg")

        album.withMode(0, stillWorks = { album.list() is Result.Success }) {
            backUpToStick(photos, stick)

            assertEquals(listOf(Path("album")), (read as MirrorReport).failures.map { it.relative })
            assertEquals(mapOf(Path("album", "a.jpg") to recorded), lastRun)
        }
        backUpToStick(photos, stick)

        assertEquals(listOf(Path("album", "a.jpg")), (read as MirrorReport).unchanged.map { it.relative })
    }

    @Test
    fun `the stick backup keeps the record of what a run with an empty source kept and copies nothing next time`() {
        createDirectoriesRaw(photos, mode = 0b111_101_101)
        createDirectoriesRaw(stick, mode = 0b111_101_101)
        val aside = (dir / "aside").also { createDirectoriesRaw(it, mode = 0b111_101_101) }
        (photos / "a.jpg").writeRaw("aaa")
        (photos / "b.jpg").writeRaw("bbb")
        backUpToStick(photos, stick)
        val recorded = lastRun
        for (name in listOf("a.jpg", "b.jpg")) SystemFileSystem.atomicMove(photos / name, aside / name)

        backUpToStick(photos, stick)
        val emptyRun = read as MirrorReport
        assertTrue(emptyRun.sourceEmpty)
        assertEquals(2, emptyRun.unclean.filterIsInstance<MirrorReport.Unclean.Kept>().size, "${emptyRun.unclean}")
        assertEquals(recorded, lastRun)

        for (name in listOf("a.jpg", "b.jpg")) SystemFileSystem.atomicMove(aside / name, photos / name)
        backUpToStick(photos, stick)

        assertEquals(emptyList(), reports)
        val report = read as MirrorReport
        assertEquals(emptyList(), report.copied)
        assertEquals(listOf(Path("a.jpg"), Path("b.jpg")), report.unchanged.map { it.relative })
    }

    /** The read that records what it loaded from docs/reading.md, as written there. */
    private fun loadRecording(config: Path) {
        var loaded: FileInfo? = null
        val text = config
            .readText(
                maxBytes = 1_048_576,
                accept = { info ->
                    loaded = info
                    true
                },
            )
            .getOrElse { return report(it) }
        println("loaded ${text.length} characters of $config, written ${loaded?.modifiedAt}")
        read = loaded
    }

    @Test
    fun `the recording read hands over the info of the file it read`() {
        val config = (dir / "app.toml").writeRaw("a = 1")
        config.setModifiedAt(Instant.parse("2001-02-03T04:05:06Z")).assertSuccess()

        loadRecording(config)

        assertEquals(emptyList(), reports)
        assertEquals(listOf("loaded 5 characters of $config, written 2001-02-03T04:05:06Z"), printed)
        assertEquals(config.info().assertSuccess(), read)
    }

    /** The half-written check from docs/writing.md, as written there. */
    private fun saveSettings(
        settings: Path,
        text: String,
    ) {
        when (settings.writeText(text).getOrElse { return report(it) }.way) {
            WriteWay.Renamed -> Unit
            WriteWay.InPlace -> println("$settings was written in place, so readers may have seen it half-written")
        }
    }

    @Test
    fun `the half-written check says so only for a write in place`() {
        val settings = dir / "settings.toml"
        saveSettings(settings, "a = 1")
        val other = dir / "other.toml"
        Files.createLink(Paths.get(other.toString()), Paths.get(settings.toString()))

        saveSettings(settings, "a = 2")

        assertEquals(emptyList(), reports)
        assertEquals(listOf("$settings was written in place, so readers may have seen it half-written"), printed)
        assertEquals("a = 2", other.readRaw())
    }

    /** The file copy from docs/writing.md, as written there, without its writer line. */
    private fun copyScript(
        script: Path,
        backup: Path,
    ) {
        when (script.copyTo(backup / script.name, keepAttributes = true).getOrElse { return report(it) }) {
            is Written -> println("backed up $script")
            is Copied.Unchanged -> println("$script is its own backup already")
        }
    }

    @Test
    fun `the file copy says when the script is its own backup`() {
        val script = (dir / "bin" / "tool.sh").also { createDirectoriesRaw(dir / "bin") }.writeRaw("#!/bin/sh\n")

        copyScript(script, dir / "backup")
        copyScript(script, dir / "bin")

        assertEquals(emptyList(), reports)
        assertEquals(listOf("backed up $script", "$script is its own backup already"), printed)
        assertEquals("#!/bin/sh\n", (dir / "backup" / "tool.sh").readRaw())
    }
}

/** Stands in for what the docs' thumbnail build answers. */
private typealias Thumbnails = Int
