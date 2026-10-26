package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.setModifiedAt
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.result
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class ReadmeSnippetsJvmTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private val reports = mutableListOf<FileError>()

    private fun report(failure: FileError) {
        reports += failure
    }

    private val printed = mutableListOf<String>()

    /** Stands in for the `println` the docs' examples call, so a test can read what they print. */
    private fun println(message: String) {
        printed += message
    }

    /** The mirror from docs/walking.md, as written there. */
    private fun mirror(
        root: Path,
        published: Path,
        warn: (FileError) -> Unit,
    ) {
        val dst = published.writer().getOrElse { return warn(it) }
        val report = root.mirrorTo(dst, enter = { it.path.name != ".git" }).getOrElse { return warn(it) }
        report.failures.forEach { warn(it.error) }
        report.notRemoved.forEach { warn(it) }
        (report.held + report.passedOver).forEach { println("passed over ${it.relative}: ${it.why}") }
    }

    @Test
    fun `the mirror example copies the source and passes over its git folder and a link leading out`() {
        val root = dir / "root"
        createDirectoriesRaw(root / ".git")
        (root / "a.txt").writeRaw("a")
        Files.createSymbolicLink(Paths.get((root / "out").toString()), Paths.get("/"))
        val dst = dir / "dst"
        createDirectoriesRaw(dst)
        (dst / "stale.txt").writeRaw("s")
        val warnings = mutableListOf<FileError>()

        mirror(root, dst, warnings::add)

        assertEquals(emptyList(), warnings)
        assertEquals(listOf("passed over .git: Refused", "passed over out: LeadsOutside"), printed)
        assertEquals(listOf(dst / "a.txt"), SystemFileSystem.list(dst))
    }

    @Test
    fun `the mirror example warns of a source that is a file and leaves the destination alone`() {
        val root = (dir / "a.txt").writeRaw("a")
        val dst = dir / "dst"
        createDirectoriesRaw(dst)
        (dst / "b.txt").writeRaw("b")
        val warnings = mutableListOf<FileError>()

        mirror(root, dst, warnings::add)

        assertEquals(listOf<FileError>(FileError.NotADirectory(root, FileType.Regular)), warnings)
        assertEquals(listOf(dst / "b.txt"), SystemFileSystem.list(dst))
    }

    private val siteReports = mutableListOf<MirrorReport>()

    /** The site builder from docs/walking.md, as written there, keeping each report in [siteReports]. */
    private fun buildSite(
        pages: Path,
        siteFolder: Path,
        render: (String) -> String,
        warn: (FileError) -> Unit,
    ) {
        fun pageOutput(relative: Path): String {
            val name = relative.toString().replace('\\', '/')
            return if (relative.extension == "md") "${name.removeSuffix(".md")}.html" else name
        }

        val site = siteFolder.writer().getOrElse { return warn(it) }
        val report = pages.mirrorTo(
            site,
            symlinks = Symlinks.Inside,
            keep = { it == Path(".git") || it == Path("CNAME") },
            outputOf = ::pageOutput,
            isCurrent = { source, existing ->
                if (source.path.extension == "md") {
                    existing.modifiedAt == source.modifiedAt
                } else {
                    existing.size == source.size && existing.modifiedAt == source.modifiedAt
                }
            },
            copy = { source, out, name ->
                val written = if (source.path.extension == "md") {
                    source.path
                        .readText(maxBytes = 1_048_576)
                        .flatMap { out.writeText(name, render(it), modifiedAt = source.modifiedAt) }
                } else {
                    out.copyFrom(source.path, name, modifiedAt = source.modifiedAt)
                }
                written.map { }
            },
        ).getOrElse { return warn(it) }
        siteReports += report
    }

    @Test
    fun `the site builder renders each page under its html name copies the rest and keeps what only the site has`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages / "posts")
        (pages / "posts" / "hello.md").writeRaw("# hello")
        (pages / "style.css").writeRaw("body {}")
        val site = dir / "site"
        createDirectoriesRaw(site / ".git")
        (site / ".git" / "HEAD").writeRaw("ref")
        (site / "CNAME").writeRaw("example.org")
        (site / "old.html").writeRaw("old")
        val render = { markdown: String -> "<h1>${markdown.removePrefix("# ")}</h1>" }

        buildSite(pages, site, render) { error("no warning expected: $it") }
        buildSite(pages, site, render) { error("no warning expected: $it") }
        val (first, second) = siteReports

        assertEquals("<h1>hello</h1>", (site / "posts" / "hello.html").readRaw())
        assertEquals("body {}", (site / "style.css").readRaw())
        assertEquals("ref", (site / ".git" / "HEAD").readRaw())
        assertEquals(
            setOf(site / ".git", site / "CNAME", site / "posts", site / "style.css"),
            SystemFileSystem.list(site).toSet(),
        )
        assertEquals(listOf(Path("old.html")), first.removed)
        val unchanged = setOf(
            Mirrored(Path("posts", "hello.md"), Path("posts", "hello.html")),
            Mirrored(Path("style.css"), Path("style.css")),
        )
        assertEquals(unchanged, second.unchanged.toSet())
    }

    @Test
    fun `the site builder copies an asset with its source's modification time`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        (pages / "style.css").writeRaw("body {}")
        (pages / "style.css").setModifiedAt(Instant.parse("2001-02-03T04:05:06Z")).assertSuccess()
        val site = dir / "site"

        buildSite(pages, site, render = { it }) { error("no warning expected: $it") }

        assertEquals(
            (pages / "style.css").info().assertSuccess().modifiedAt,
            (site / "style.css").info().assertSuccess().modifiedAt,
        )
    }

    @Test
    fun `the site builder gives an asset that is 600 in the source the mode of a new shared file`() {
        val newFile = sharedFileBits(dir / "probe")
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        (pages / "style.css").writeRaw("body {}")
        (pages / "style.css").chmod(0b110_000_000).assertSuccess()
        val site = dir / "site"

        buildSite(pages, site, render = { it }) { error("no warning expected: $it") }

        assertEquals(newFile, (site / "style.css").permissions().assertSuccess()?.bits)
    }

    @Test
    fun `the site builder copies an asset again once it is replaced by an older file`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        val logo = (pages / "logo.png").writeRaw("logo-v1")
        logo.setModifiedAt(Instant.parse("2020-01-01T00:00:00Z")).assertSuccess()
        val site = dir / "site"

        buildSite(pages, site, render = { it }) { error("no warning expected: $it") }
        logo.writeRaw("logo-v2")
        logo.setModifiedAt(Instant.parse("2010-01-01T00:00:00Z")).assertSuccess()
        buildSite(pages, site, render = { it }) { error("no warning expected: $it") }

        assertEquals("logo-v2", (site / "logo.png").readRaw())
        assertEquals(listOf(Mirrored(Path("logo.png"), Path("logo.png"))), siteReports.last().copied)
    }

    @Test
    fun `the site builder renders a page again once its source is replaced by an older file`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        val page = (pages / "post.md").writeRaw("# new")
        page.setModifiedAt(Instant.parse("2020-01-01T00:00:00Z")).assertSuccess()
        val site = dir / "site"
        val render = { markdown: String -> "<h1>${markdown.removePrefix("# ")}</h1>" }

        buildSite(pages, site, render) { error("no warning expected: $it") }
        page.writeRaw("# restored")
        page.setModifiedAt(Instant.parse("2010-01-01T00:00:00Z")).assertSuccess()
        buildSite(pages, site, render) { error("no warning expected: $it") }

        assertEquals("<h1>restored</h1>", (site / "post.html").readRaw())
        assertEquals(listOf(Mirrored(Path("post.md"), Path("post.html"))), siteReports.last().copied)
    }

    @Test
    fun `the site builder renders each link to a page as the page`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        (pages / "a.md").writeRaw("# a")
        (pages / "b.md").createSymlink(Path("a.md")).assertSuccess()
        val site = dir / "site"

        buildSite(pages, site, render = { it.removePrefix("# ") }) { error("no warning expected: $it") }

        assertEquals("a", (site / "b.html").readRaw())
        assertTrue(siteReports.single().isClean)
    }

    @Test
    fun `the site builder reports a page past its size cap and writes no html for it`() {
        val pages = dir / "pages"
        createDirectoriesRaw(pages)
        (pages / "huge.md").writeRaw("a".repeat(1_048_577))
        val site = dir / "site"

        buildSite(pages, site, render = { it }) { error("no warning expected: $it") }

        val failure = siteReports.single().failures.single()
        assertEquals(Path("huge.md"), failure.relative)
        assertTrue(failure.error is FileError.TooLarge, "$failure")
        assertFalse(SystemFileSystem.exists(site / "huge.html"))
    }

    /** The backup tool from docs/walking.md, as written there. */
    private fun backUp(
        disk: Path,
        backupDisk: Path,
        warn: (FileError) -> Unit,
    ) {
        fun fewRemovals(plan: RemovalPlan): List<Removal> {
            val wanted = plan.defaultApproved()
            return if (wanted.size > 50) emptyList() else wanted
        }

        val backup = backupDisk.writer(create = false).getOrElse { return warn(it) }
        val report = disk.mirrorTo(backup, approveRemovals = ::fewRemovals).getOrElse { return warn(it) }
        if (report.removalsDeclined.isNotEmpty()) {
            println("${report.removalsDeclined.size} entries kept in the backup, check $disk")
        }
    }

    @Test
    fun `the backup tool removes nothing when a disk holding only lost+found would empty the backup`() {
        val disk = dir / "disk"
        createDirectoriesRaw(disk / "lost+found")
        val backup = dir / "backup"
        createDirectoriesRaw(backup)
        repeat(60) { (backup / "photo$it.jpg").writeRaw("p") }

        backUp(disk, backup) { error("no warning expected: $it") }

        assertEquals(listOf("60 entries kept in the backup, check $disk"), printed)
        assertEquals(61, SystemFileSystem.list(backup).size)
    }

    @Test
    fun `the backup tool removes nothing when a disk mounts with nothing on it`() {
        val disk = dir / "disk"
        createDirectoriesRaw(disk)
        val backup = dir / "backup"
        createDirectoriesRaw(backup)
        repeat(3) { (backup / "photo$it.jpg").writeRaw("p") }

        backUp(disk, backup) { error("no warning expected: $it") }

        assertEquals(listOf("3 entries kept in the backup, check $disk"), printed)
        assertEquals(3, SystemFileSystem.list(backup).size)
    }

    @Test
    fun `the backup tool removes what a disk no longer holds when it is a few entries`() {
        val disk = dir / "disk"
        createDirectoriesRaw(disk)
        (disk / "a.jpg").writeRaw("a")
        val backup = dir / "backup"
        createDirectoriesRaw(backup)
        (backup / "gone.jpg").writeRaw("g")

        backUp(disk, backup) { error("no warning expected: $it") }

        assertEquals(emptyList(), printed)
        assertEquals(listOf(backup / "a.jpg"), SystemFileSystem.list(backup))
    }

    @Test
    fun `the backup tool keeps the copy of a folder that is now a link leading out of the disk`() {
        val disk = dir / "disk"
        createDirectoriesRaw(disk / "photos")
        (disk / "photos" / "a.jpg").writeRaw("a")
        (disk / "notes.md").writeRaw("n")
        (disk / "old.md").writeRaw("o")
        val backup = dir / "backup"
        createDirectoriesRaw(backup)
        backUp(disk, backup) { error("no warning expected: $it") }
        SystemFileSystem.delete(disk / "photos" / "a.jpg")
        SystemFileSystem.delete(disk / "photos")
        SystemFileSystem.delete(disk / "old.md")
        (disk / "photos").createSymlink(Path("/nonexistent/share/photos")).assertSuccess()

        backUp(disk, backup) { error("no warning expected: $it") }

        assertEquals(listOf("2 entries kept in the backup, check $disk"), printed)
        assertEquals("a", (backup / "photos" / "a.jpg").readRaw())
        assertFalse(SystemFileSystem.exists(backup / "old.md"))
    }

    @Test
    fun `the backup tool makes nothing where the backup disk is not mounted`() {
        val disk = dir / "disk"
        createDirectoriesRaw(disk)
        (disk / "a.jpg").writeRaw("a")
        val warned = mutableListOf<FileError>()

        backUp(disk, dir / "mnt" / "backup") { warned += it }

        assertEquals(listOf<FileError>(FileError.NotFound(dir / "mnt" / "backup")), warned)
        assertFalse(SystemFileSystem.exists(dir / "mnt"))
    }

    /** The command-line startup from docs/run-temp.md, as written there. */
    private fun run(
        dirs: AppDirs,
        runApp: (Writer) -> Int,
    ): Int {
        val instance = dirs.singleInstance("app").getOrElse { failure ->
            val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
            println("not started, already running as pid $pid: $failure")
            return 1
        }
        return instance.use {
            dirs.clearStaleTempRuns().fold(
                onSuccess = { cleanup -> cleanup.failed.forEach { println("old run ${it.folder} left: ${it.error}") } },
                onError = { failure -> println("old runs not cleared: $failure") },
            )
            dirs.withRunTemp { temp -> runApp(temp) }.getOrElse { failure ->
                println("no run temp: $failure")
                1
            }
        }
    }

    private fun appDirs(
        runtime: Path? = null,
        runId: String = "2b1f6c3e-8d4a-4f0e-9a7b-5c3d2e1f0a9b",
    ): AppDirs = AppDirs(
        config = dir / "config",
        data = dir / "data",
        state = dir / "state",
        cache = dir / "cache",
        temp = dir / "tmp" / "app" / runId,
        runtime = runtime,
    )

    @Test
    fun `the startup recipe leaves this process's pid, clears a crashed run and runs in its own temp`() {
        val dirs = appDirs()
        val crashed = dir / "tmp" / "app" / A_WRITE_ID
        createDirectoriesRaw(crashed, OWNER_ONLY_FOLDER)
        var ranIn: Path? = null

        val code = run(dirs) { temp ->
            ranIn = temp.root
            0
        }

        assertEquals(0, code)
        assertEquals(dirs.temp, ranIn)
        assertFalse(SystemFileSystem.exists(crashed))
        assertEquals("${ProcessHandle.current().pid()}\n", (dirs.state / "app.pid").readRaw())
    }

    @Test
    fun `the startup recipe keeps its lock and pid in the state folder also when there is a runtime folder`() {
        val dirs = appDirs(runtime = dir / "run" / "app")

        assertEquals(0, run(dirs) { 0 })

        assertEquals("${ProcessHandle.current().pid()}\n", (dirs.state / "app.pid").readRaw())
        assertFalse(SystemFileSystem.exists(dir / "run"))
    }

    @Test
    fun `the startup recipe keeps out a second run that has no runtime folder`() {
        val withRuntime = appDirs(runtime = dir / "run" / "app")

        val withoutRuntime = appDirs(runId = "7c9e2a41-3b5d-4e8f-a6c1-0d2b4f6e8a13")

        val second = run(withRuntime) { run(withoutRuntime) { 0 } }

        assertEquals(1, second)
    }

    @Test
    fun `the startup recipe answers 1 while another run holds the lock`() {
        val dirs = appDirs()
        val lock = dirs.state / ".app.lock"
        createDirectoriesRaw(dirs.state)

        val code = lock
            .tryLock()
            .getOrElse { error("not locked: $it") }
            .use { run(dirs) { 0 } }

        assertEquals(1, code)
    }

    /** The install from docs/folders-and-moves.md, as written there. */
    private fun AppDirs.install(
        name: String,
        version: String,
        files: Map<String, ByteArray>,
    ): EmptyResult<FileError> = result {
        val packages = cache.writer().bind()
        packages.createDirectoryAtomically("$name/$version") { stage ->
            // A name such as ../evil is InvalidRelativePath, written nowhere, and the stage then goes.
            for ((file, bytes) in files) stage.writeBytes(file, bytes).bind()
        }.bind()
    }

    @Test
    fun `the install puts a whole version in place once and refuses it a second time`() {
        val dirs = appDirs()
        val files = mapOf("bin/tool" to "#!/bin/sh".encodeToByteArray(), "README" to "r".encodeToByteArray())
        val version = dirs.cache / "tool" / "1.0"

        assertEquals(Ok(Unit), dirs.install("tool", "1.0", files))
        assertEquals(Err(FileError.AlreadyExists(version, FileType.Directory)), dirs.install("tool", "1.0", files))

        assertEquals("#!/bin/sh", (version / "bin" / "tool").readRaw())
        assertEquals(setOf(version / "README", version / "bin"), SystemFileSystem.list(version).toSet())
        assertEquals(listOf(version), SystemFileSystem.list(dirs.cache / "tool"))
    }

    @Test
    fun `the install of a package naming a file outside it leaves no version and writes nothing outside`() {
        val dirs = appDirs()
        val files = mapOf("a" to "a".encodeToByteArray(), "../evil" to "e".encodeToByteArray())

        val installed = dirs.install("tool", "1.0", files)

        assertEquals(FileError.InvalidRelativePath::class, installed.errorOrNull()?.let { it::class })
        assertEquals(emptyList(), SystemFileSystem.list(dirs.cache / "tool"))
    }

    /** The install that leaves out a refused name from docs/folders-and-moves.md, as written there. */
    private fun AppDirs.installLeavingOut(
        name: String,
        version: String,
        files: Map<String, ByteArray>,
    ): EmptyResult<FileError> = result {
        val packages = cache.writer().bind()
        packages.createDirectoryAtomically("$name/$version") { stage ->
            for ((file, bytes) in files) {
                stage.writeBytes(file, bytes).onError { failure ->
                    if (failure is FileError.NameRefused) {
                        println("left out $file: $failure")
                        continue
                    }
                }.bind()
            }
        }.bind()
    }

    @Test
    fun `the install leaving out refused names places the rest of the version and writes nothing outside`() {
        val dirs = appDirs()
        val long = "x".repeat(300)
        val files = linkedMapOf(
            "bin/tool" to "t".encodeToByteArray(),
            "../evil" to "e".encodeToByteArray(),
            "/abs/path" to "a".encodeToByteArray(),
            long to "l".encodeToByteArray(),
            "late" to "z".encodeToByteArray(),
        )
        val version = dirs.cache / "tool" / "1.0"

        val installed = dirs.installLeavingOut("tool", "1.0", files)

        assertEquals(Ok(Unit), installed)
        val leftOut = listOf("left out ../evil", "left out /abs/path", "left out $long")
        assertEquals(leftOut, printed.map { it.substringBefore(":") })
        assertEquals(setOf(version / "bin", version / "late"), SystemFileSystem.list(version).toSet())
        assertEquals(listOf(dirs.cache / "tool"), SystemFileSystem.list(dirs.cache))
    }

    /** The hooks install from docs/folders-and-moves.md, as written there. */
    private fun AppDirs.installHooks(hooks: Map<String, String>): EmptyResult<FileError> = result {
        val tools = data.writer().bind()
        tools.createDirectoryAtomically("tool") { stage ->
            for ((name, script) in hooks) {
                stage.writeText("hooks/$name", script, executable = true)
                    .onError { println("hooks/$name not written: $it") }
                    .bind()
            }
        }.bind()
    }

    @Test
    fun `the hooks install names a file it could not write by its place in the folder`() {
        val dirs = appDirs()
        val long = "x".repeat(256)

        val failed = dirs.installHooks(mapOf("sync.sh" to "#!/bin/sh\n", long to "#!/bin/sh\n"))

        val folder = failed.assertError<FileError.InvalidRelativePath>().folder
        assertEquals("hooks/$long", printed.single().substringBefore(" not written"))
        assertEquals(dirs.data / "tool", folder)
        assertEquals(false, SystemFileSystem.exists(dirs.data / "tool"))
        assertEquals(Ok(Unit), dirs.installHooks(mapOf("sync.sh" to "#!/bin/sh\n")))
        assertEquals("#!/bin/sh\n", (dirs.data / "tool" / "hooks" / "sync.sh").readRaw())
    }

    /** The rotated log from docs/appending.md, as written there. */
    private fun appendAll(
        dirs: AppDirs,
        lines: Sequence<String>,
        report: (FileError) -> Unit,
    ) {
        val state = dirs.state.writer().getOrElse { return report(it) }
        val log = state.openAppend("app.log", Reopen.WhenMoved(), replaceLink = true).getOrElse { return report(it) }
        log.use { for (line in lines) it.appendLine(line).onError { failure -> report(failure) } }
    }

    @Test
    fun `the Reopen example lands the lines after a rotation in the new log`() {
        val dirs = appDirs()
        val path = dirs.state / "app.log"
        val reports = mutableListOf<FileError>()
        val lines = sequence {
            yield("before")
            SystemFileSystem.atomicMove(path, dirs.state / "app.log.1")
            yield("after")
        }

        appendAll(dirs, lines, reports::add)

        assertEquals(emptyList(), reports)
        assertEquals("before\n", (dirs.state / "app.log.1").readRaw())
        assertEquals("after\n", path.readRaw())
    }

    @Test
    fun `the Reopen example puts a new log in place of a link at the log and appends nothing through it`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.state)
        val outside = (dir / "outside.log").writeRaw("theirs\n")
        val link = Paths.get((dirs.state / "app.log").toString())
        Files.createSymbolicLink(link, Paths.get(outside.toString()))
        val reports = mutableListOf<FileError>()

        appendAll(dirs, sequenceOf("mine"), reports::add)

        assertEquals(emptyList(), reports)
        assertFalse(Files.isSymbolicLink(link))
        assertEquals("mine\n", (dirs.state / "app.log").readRaw())
        assertEquals("theirs\n", outside.readRaw())
    }

    @Test
    fun `the Reopen example puts a new log in place of a link put at the log after a rotation`() {
        val dirs = appDirs()
        val path = dirs.state / "app.log"
        val outside = (dir / "outside.log").writeRaw("theirs\n")
        val reports = mutableListOf<FileError>()
        val lines = sequence {
            yield("before")
            SystemFileSystem.atomicMove(path, dirs.state / "app.log.1")
            Files.createSymbolicLink(Paths.get(path.toString()), Paths.get(outside.toString()))
            yield("after")
        }

        appendAll(dirs, lines, reports::add)

        assertEquals(emptyList(), reports)
        assertEquals("before\n", (dirs.state / "app.log.1").readRaw())
        assertFalse(Files.isSymbolicLink(Paths.get(path.toString())))
        assertEquals("after\n", path.readRaw())
        assertEquals("theirs\n", outside.readRaw())
    }

    /** The recovery loop from docs/leftovers.md, as written there. */
    private fun recover(
        dirs: AppDirs,
        confirm: (String) -> Boolean,
    ) {
        val config = dirs.config.writer().getOrElse { return report(it) }
        val scan = config.leftovers().getOrElse { return report(it) }
        for ((file, leftovers) in scan.byFile()) {
            val backups = leftovers.filterIsInstance<Leftover.Backup>()
            val oldest = backups.firstOrNull() ?: continue
            if (!confirm("$file may be cut short; restore it?")) continue
            config.restore(oldest).onError {
                report(it)
                continue
            }
            for (backup in backups.drop(1)) config.discard(backup).onError { report(it) }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the recovery loop restores the oldest backup of each file and discards the later ones`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.config)
        val settings = (dirs.config / "app.toml").writeRaw("a = ")
        val keys = (dirs.config / "keys.toml").writeRaw("k = ")
        val older = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_000))
        val newer = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(2_000))
        (dirs.config / ".app.toml.$older.bak").writeRaw("a = 1\nb = 2")
        (dirs.config / ".app.toml.$newer.bak").writeRaw("a = 1")
        (dirs.config / ".keys.toml.$newer.bak").writeRaw("k = 1")
        val asked = mutableListOf<String>()

        recover(dirs) { question ->
            asked += question
            true
        }

        assertEquals(emptyList(), reports)
        assertEquals(listOf("$settings may be cut short; restore it?", "$keys may be cut short; restore it?"), asked)
        assertEquals("a = 1\nb = 2", settings.readRaw())
        assertEquals("k = 1", keys.readRaw())
        assertEquals(setOf(settings, keys), SystemFileSystem.list(dirs.config).toSet())
    }

    @Test
    fun `the recovery loop reports a backup beside a link it will not restore through and keeps it`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.config)
        val elsewhere = dir / "elsewhere"
        createDirectoriesRaw(elsewhere)
        val settings = (elsewhere / "app.toml").writeRaw("a = ")
        val link = Paths.get((dirs.config / "app.toml").toString())
        Files.createSymbolicLink(link, Paths.get(settings.toString()))
        val backup = (dirs.config / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")

        recover(dirs) { true }
        Files.delete(link)

        assertEquals(listOf<FileError>(FileError.NotRegularFile(dirs.config / "app.toml", FileType.Symlink)), reports)
        assertEquals("a = ", settings.readRaw())
        assertEquals("a = 1", backup.readRaw())
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `the recovery loop keeps every backup when the restore is declined`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.config)
        val settings = (dirs.config / "app.toml").writeRaw("a = ")
        val config = dirs.config
        val older = config / ".app.toml.${Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_000))}.bak"
        val newer = config / ".app.toml.${Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(2_000))}.bak"
        older.writeRaw("a = 1\nb = 2")
        newer.writeRaw("a = 1")

        recover(dirs) { false }

        assertEquals(emptyList(), reports)
        assertEquals("a = ", settings.readRaw())
        assertEquals(setOf(settings, older, newer), SystemFileSystem.list(config).toSet())
    }

    /** The folder scan from docs/leftovers.md, as written there. */
    private fun scanLeftovers(dirs: AppDirs) {
        val scan = dirs.config.leftoversIn().orIfNotFound { LeftoverScan.EMPTY }.getOrElse { return report(it) }
        scan.failed.forEach { report(it.error) }
        (scan.found + scan.throughLinks).forEach { println("left over: ${it.copy}") }
    }

    @Test
    fun `the leftovers scan reads a config folder not made yet as nothing left over`() {
        scanLeftovers(appDirs())

        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), printed)
    }

    @Test
    fun `the leftovers scan names a temp in the folder and one beside the file a link there leads to`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.config)
        createDirectoriesRaw(dirs.data)
        val inside = (dirs.config / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a")
        val outside = (dirs.data / ".db.toml.$A_WRITE_ID.tmp").writeRaw("b")
        val link = Paths.get((dirs.config / "db.toml").toString())
        Files.createSymbolicLink(link, Paths.get((dirs.data / "db.toml").toString()))

        scanLeftovers(dirs)
        Files.delete(link)

        assertEquals(emptyList(), reports)
        val expected = listOf(
            "left over: $inside",
            "left over: $outside",
        )
        assertEquals(expected, printed)
    }

    /** The hook from docs/writing.md, as written there. */
    private fun writeHook(
        dirs: AppDirs,
        report: (FileError) -> Unit,
    ) {
        (dirs.data / "hook.sh").writeText("#!/bin/sh\nexec tool sync\n", executable = true).onError { return report(it) }
    }

    @Test
    fun `the hook is written runnable`() {
        val dirs = appDirs()
        val reports = mutableListOf<FileError>()

        writeHook(dirs, reports::add)

        val mode = (dirs.data / "hook.sh").permissions().assertSuccess()
        assertEquals(emptyList(), reports)
        assertTrue(mode == null || mode.ownerExecute)
    }

    private sealed interface AppError : ResultError {
        data class File(val error: FileError) : AppError
    }

    /** The lock answer in an app's own error from docs/locking.md, as written there. */
    private fun readLocked(
        lockFile: Path,
        config: Path,
    ): Result<String, AppError> {
        val text: Result<String, AppError> = result {
            lockFile.withLock { config.readText(maxBytes = 65_536).bind(AppError::File) }.bind(AppError::File)
        }
        return text
    }

    @Test
    fun `the lock answer bound in a result block carries the work's error as the app's`() {
        val config = dir / "app.toml"

        assertEquals(Err(AppError.File(FileError.NotFound(config))), readLocked(dir / ".app.lock", config))
        assertEquals(Ok("a = 1"), readLocked(dir / ".app.lock", config.writeRaw("a = 1")))
    }

    /** The secret file from docs/writing.md, as written there. */
    private fun saveToken(
        dirs: AppDirs,
        token: String,
        report: (FileError) -> Unit,
    ) {
        val secrets = (dirs.state / "secrets").privateWriter().getOrElse { return report(it) }
        secrets.writeText("token", token).onError { return report(it) }
    }

    @Test
    fun `the secret file reports a link at its path and writes nothing through it`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.state)
        createDirectoriesRaw(dirs.state / "secrets", OWNER_ONLY_FOLDER)
        val theirs = (dir / "theirs").writeRaw("theirs")
        val link = Paths.get((dirs.state / "secrets" / "token").toString())
        Files.createSymbolicLink(link, Paths.get(theirs.toString()))
        val reports = mutableListOf<FileError>()

        saveToken(dirs, "s3cret", reports::add)

        val state = dirs.state.realPath().assertSuccess()
        assertEquals(
            listOf<FileError>(FileError.NotRegularFile(state / "secrets" / "token", FileType.Symlink)),
            reports,
        )
        assertEquals("theirs", theirs.readRaw())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `the secret file's folder is made for the owner alone`() {
        val dirs = appDirs()
        val reports = mutableListOf<FileError>()

        saveToken(dirs, "s3cret", reports::add)

        assertEquals(emptyList(), reports)
        assertEquals(0b111_000_000, (dirs.state / "secrets").permissions().assertSuccess()?.bits)
        assertEquals(0b110_000_000, (dirs.state / "secrets" / "token").permissions().assertSuccess()?.bits)
    }

    @Test
    fun `the secret file is saved in a state folder a shared writer made for a log`() {
        val dirs = appDirs()
        dirs.state.writer().assertSuccess().writeText("app.log", "started\n").assertSuccess()
        dirs.state.chmod(0b111_101_101).assertSuccess()
        val reports = mutableListOf<FileError>()

        saveToken(dirs, "s3cret", reports::add)

        assertEquals(emptyList(), reports)
        assertEquals("s3cret", (dirs.state / "secrets" / "token").readRaw())
    }

    /** The hard link check from docs/writing.md, as written there. */
    private fun writeSaying(
        settings: Path,
        text: String,
    ) {
        val atomic = settings.writeText(text, WriteMode.Atomic)
        val refusal = (atomic.errorOrNull() as? FileError.AtomicRefused)?.why
        if (refusal == AtomicRefusal.OtherHardLinks) {
            settings.writeText(text, WriteMode.Replace).onError { return report(it) }
            println("$settings had other hard links, which keep the old content")
        } else {
            atomic.onError { return report(it) }
        }
    }

    @Test
    fun `the hard link check writes a linked file again with Replace and says its links keep the old content`() {
        val settings = (dir / "app.toml").writeRaw("old")
        val other = dir / "other.toml"
        Files.createLink(Paths.get(other.toString()), Paths.get(settings.toString()))

        writeSaying(settings, "new")

        assertEquals(emptyList(), reports)
        assertEquals("new", settings.readRaw())
        assertEquals("old", other.readRaw())
        assertEquals(listOf("$settings had other hard links, which keep the old content"), printed)
    }

    @Test
    fun `the hard link check writes a file with no other links by rename and says nothing`() {
        val settings = (dir / "app.toml").writeRaw("old")

        writeSaying(settings, "new")

        assertEquals(emptyList(), reports)
        assertEquals("new", settings.readRaw())
        assertEquals(emptyList(), printed)
    }

    /** The escaping link example from docs/writer.md, as written there. */
    private fun save(
        dst: Path,
        text: String,
        report: (FileError) -> Unit,
    ) {
        val config = dst.writer().getOrElse { return report(it) }
        config.writeText("sub/app.toml", text).onError { failure ->
            when (failure) {
                is FileError.Escapes -> println("${failure.path} leads to ${failure.resolved}")
                else -> report(failure)
            }
        }
    }

    @Test
    fun `the writer example writes nothing through a link leading out of the folder`() {
        val dst = dir / "dst"
        val elsewhere = dir / "elsewhere"
        createDirectoriesRaw(dst)
        createDirectoriesRaw(elsewhere)
        Files.createSymbolicLink(Paths.get((dst / "sub").toString()), Paths.get(elsewhere.toString()))
        val reports = mutableListOf<FileError>()

        save(dst, "a = 1", reports::add)
        Files.delete(Paths.get((dst / "sub").toString()))

        assertEquals(emptyList(), reports)
        assertEquals(listOf("${dst / "sub" / "app.toml"} leads to $elsewhere"), printed)
        assertEquals(emptyList(), SystemFileSystem.list(elsewhere))
    }

    /** The README's name check from Path names, as written there. */
    private fun save(
        dirs: AppDirs,
        name: String,
        text: String,
    ) {
        name.nameProblem()?.let { problem -> return println("$name is not a file name: $problem") }
        (dirs.data / name).writeText(text).onError { return report(it) }
    }

    @Test
    fun `the name check writes a plain name and refuses one that climbs out of the folder`() {
        val dirs = appDirs()

        save(dirs, "notes.txt", "a")
        save(dirs, "../escape.txt", "b")

        assertEquals(emptyList(), reports)
        assertEquals(listOf("../escape.txt is not a file name: Separator"), printed)
        assertEquals("a", (dirs.data / "notes.txt").readRaw())
        assertFalse(SystemFileSystem.exists(dir / "escape.txt"))
    }

    /** The README's manifest check from Path names, as written there. */
    private fun unpack(
        stage: Writer,
        manifest: Map<String, ByteArray>,
    ) {
        for ((entry, bytes) in manifest) {
            entry.relativePathProblem()?.let { problem ->
                return println("$entry is not a path in the package: $problem")
            }
            stage.writeBytes(entry, bytes).onError { return report(it) }
        }
    }

    @Test
    fun `the manifest check writes the paths below the stage and stops at one that climbs out`() {
        val stage = (dir / "stage").also { createDirectoriesRaw(it) }
        val manifest = linkedMapOf(
            "bin/tool" to "a".encodeToByteArray(),
            "../escape" to "b".encodeToByteArray(),
            "late" to "c".encodeToByteArray(),
        )

        unpack(stage.writer().assertSuccess(), manifest)

        assertEquals(emptyList(), reports)
        val refused = "../escape is not a path in the package: BadName(index=0, name=.., problem=DotDot)"
        assertEquals(listOf(refused), printed)
        assertEquals("a", (stage / "bin" / "tool").readRaw())
        assertEquals(listOf(stage / "bin"), SystemFileSystem.list(stage))
    }

    /** The link switch from docs/folders-and-moves.md, as written there. */
    private fun switchRelease(dirs: AppDirs) {
        val data = dirs.data.writer().getOrElse { return report(it) }
        data.switchSymlink("current", Path("release-43")).onError { return report(it) }
    }

    @Test
    fun `the link switch example points current at the new release`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.data / "release-42")
        createDirectoriesRaw(dirs.data / "release-43")
        (dirs.data / "current").createSymlink(Path("release-42")).assertSuccess()

        switchRelease(dirs)

        assertEquals(emptyList(), reports)
        assertEquals(Ok(Path("release-43")), (dirs.data / "current").readSymlink())
        (dirs.data / "current").delete().assertSuccess()
    }

    /** The check inside the timed lock check from docs/locking.md, with [stopServing] what it returns. */
    private fun checkLock(
        lock: FileLock,
        stopServing: () -> String,
    ): String {
        lock.check().onError { failure -> if (failure is FileError.LockLost) return stopServing() }
        return "serving"
    }

    @Test
    fun `the lost lock check stops serving once the lock file is gone`() {
        val lockFile = dir / ".app.lock"

        lockFile.tryLock().assertSuccess().use { lock ->
            assertEquals("serving", checkLock(lock) { "stopped" })
            SystemFileSystem.delete(lockFile)

            assertEquals("stopped", checkLock(lock) { "stopped" })
        }
    }

    /** The timed lock check from docs/locking.md, as written there, with [stopServing] what it returns. */
    private suspend fun watchLock(
        lock: FileLock,
        stopServing: () -> String,
    ): String {
        while (true) {
            delay(30.seconds)
            lock.check().onError { failure -> if (failure is FileError.LockLost) return stopServing() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the timed lock check stops serving at the first check after the lock file is gone`() = runTest {
        val lockFile = dir / ".app.lock"

        lockFile.tryLock().assertSuccess().use { lock ->
            launch {
                delay(45.seconds)
                SystemFileSystem.delete(lockFile)
            }

            assertEquals("stopped", watchLock(lock) { "stopped" })
            assertEquals(60.seconds.inWholeMilliseconds, testScheduler.currentTime)
        }
    }

    private val built = mutableListOf<Path>()

    private var duringBuild: () -> Unit = {}

    /** Stands in for the build the keyed instance example guards, recording where it built. */
    private fun build(into: Path) {
        built += into
        duringBuild()
    }

    /** The keyed instance from docs/locking.md, as written there. */
    private fun buildOnce(
        dirs: AppDirs,
        outputFolder: Path,
    ) {
        val instance = dirs.singleInstance(outputFolder).getOrElse { return report(it) }
        instance.use { build(into = outputFolder) }
    }

    @Test
    fun `the keyed instance example keeps out a second build into the same folder`() {
        val dirs = appDirs()
        val out = dir / "out"
        duringBuild = {
            duringBuild = {}
            buildOnce(dirs, dir / "src" / ".." / "out")
        }

        buildOnce(dirs, out)

        assertEquals(listOf(out), built)
        assertEquals(FileError.LockBusy::class, reports.single()::class)
    }

    /** The header-writing log from docs/appending.md, as written there, then given [rows]. */
    private fun appendRows(
        dirs: AppDirs,
        header: ByteArray,
        rows: Sequence<String>,
    ) {
        val state = dirs.state.writer().getOrElse { return report(it) }
        val csv = state
            .openAppend(
                "samples.csv",
                Reopen.WhenMoved(onReopen = { file, _ -> file.append(header).onError(::report) }),
            )
            .getOrElse { return report(it) }
        csv.use { for (row in rows) it.append("$row\n".encodeToByteArray()).onError(::report) }
    }

    @Test
    fun `the header example starts the log it opens again after a rotation with the header`() {
        val dirs = appDirs()
        val path = dirs.state / "samples.csv"
        val rows = sequence {
            yield("1")
            SystemFileSystem.atomicMove(path, dirs.state / "samples.csv.1")
            yield("2")
        }

        appendRows(dirs, "n\n".encodeToByteArray(), rows)

        assertEquals(emptyList(), reports)
        assertEquals("1\n", (dirs.state / "samples.csv.1").readRaw())
        assertEquals("n\n2\n", path.readRaw())
    }

    /** The app-wide scan from docs/leftovers.md, as written there. */
    private fun scanApp(dirs: AppDirs) {
        val everywhere = dirs.leftovers().getOrElse { return report(it) }
        everywhere.found.forEach { println("left over: ${it.copy}") }
    }

    @Test
    fun `the app-wide leftovers scan names a temp deep in state and passes over the folders not made`() {
        val dirs = appDirs()
        createDirectoriesRaw(dirs.state / "db")
        val temp = (dirs.state / "db" / ".index.$A_WRITE_ID.tmp").writeRaw("a")

        scanApp(dirs)

        assertEquals(emptyList(), reports)
        assertEquals(listOf("left over: $temp"), printed)
    }

    /** The FileType description from docs/types.md, as written there. */
    private fun describe(type: FileType): String = when (type) {
        FileType.Regular -> "a file"
        FileType.Directory -> "a folder"
        FileType.Fifo -> "a named pipe"
        FileType.CharacterDevice -> "a device"
        FileType.BlockDevice -> "a disk or partition"
        FileType.Socket -> "a socket"
        FileType.Symlink -> "a symlink"
        FileType.Other -> "something this platform cannot name"
    }

    @Test
    fun `the FileType description names a symlink a folder could not be moved onto`() {
        val folder = (dir / "folder").also { createDirectoriesRaw(it) }
        val link = dir / "link"
        Files.createSymbolicLink(Paths.get(link.toString()), Paths.get("missing"))

        val refused = folder.moveTo(link).assertError<FileError.NotADirectory>()
        Files.delete(Paths.get(link.toString()))

        assertEquals("a symlink", describe(refused.type))
    }

    /** The IoCause advice from docs/types.md, as written there. */
    private fun advice(cause: IoCause): String = when (cause) {
        IoCause.Denied -> "you may not do that here"
        IoCause.NotFound -> "a file or folder on the way is missing"
        IoCause.ReadOnly -> "the disk is mounted read-only"
        IoCause.NoSpace -> "the disk, or your quota on it, is full"
        IoCause.Loop -> "a symlink on the way loops, or too many follow in a row"
        IoCause.Other -> "see the exception for why"
    }

    @Test
    fun `the IoCause advice names a folder this process may not write`() {
        val folder = (dir / "locked").also { createDirectoriesRaw(it) }
        val probe = folder / "probe.txt"
        folder.withMode(0b101_000_000, stillWorks = { probe.writeText("p") is Result.Success }) {
            val refused = (folder / "a.txt").writeText("a").assertError<FileError.WriteFailed>()

            assertEquals("you may not do that here", advice(refused.cause))
        }
    }

    /** The write failure wording from docs/types.md, as written there. */
    private fun whereRefused(failure: FileError.WriteFailed): String = when (val at = failure.at) {
        null -> "could not write ${failure.path}"
        is FailedAt.Temp -> "could not write ${failure.path}: its temp ${at.path} could not be made or moved"
        is FailedAt.Backup -> "could not write ${failure.path}: its backup ${at.path} could not be made"
        is FailedAt.LinkTarget -> "could not write ${failure.path}: the file it links to, ${at.path}, refused"
        is FailedAt.Folder -> "could not write ${failure.path}: the folder ${at.path} could not be made"
        is FailedAt.Claim -> "could not write ${failure.path}: its claim ${at.path} could not be made"
    }

    @Test
    fun `the write failure wording names the file and the temp a folder this process may not write refused`() {
        val folder = (dir / "locked").also { createDirectoriesRaw(it) }
        val probe = folder / "probe.txt"
        folder.withMode(0b101_000_000, stillWorks = { probe.writeText("p") is Result.Success }) {
            val refused = (folder / "a.txt").writeText("a").assertError<FileError.WriteFailed>()
            val temp = assertIs<FailedAt.Temp>(refused.at).path

            val expected = "could not write ${folder / "a.txt"}: its temp $temp could not be made or moved"
            assertEquals(expected, whereRefused(refused))
        }
    }

    @Test
    fun `the write failure wording names the file and the folder that could not be made`() {
        val folder = (dir / "locked").also { createDirectoriesRaw(it) }
        val probe = folder / "probe.txt"
        folder.withMode(0b101_000_000, stillWorks = { probe.writeText("p") is Result.Success }) {
            val refused = (folder / "sub" / "a.txt").writeText("a").assertError<FileError.WriteFailed>()

            val expected = "could not write ${folder / "sub" / "a.txt"}: the folder ${folder / "sub"} could not be made"
            assertEquals(expected, whereRefused(refused))
        }
    }

    @Test
    fun `the exclude example leaves a backup inside the tree out also when a link leads to it`() {
        val root = dir / "root"
        val backup = root / "backup"
        createDirectoriesRaw(backup)
        (backup / "old.txt").writeRaw("old")
        val kept = (root / "a.txt").writeRaw("a")
        val link = Paths.get((root / "via").toString())
        Files.createSymbolicLink(link, Paths.get(backup.toString()))

        val walked = root
            .walkTopDown(exclude = setOf(backup))
            .assertSuccess()
            .toList()
        Files.delete(link)

        val expected = listOf(
            walkedFile(kept, Path("a.txt"), size = 1, viaLink = false),
            WalkEntry.Skipped(backup, Path("backup"), WalkSkip.Excluded),
            WalkEntry.Skipped(root / "via", Path("via"), WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }
}
