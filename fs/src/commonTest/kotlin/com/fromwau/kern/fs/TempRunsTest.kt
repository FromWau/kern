package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

    private fun run(): AppDirs = base.forApp("app", Uuid.random()).assertSuccess()

    /** The folder holding the runs' folders by its real path, as kern names it in a writer's root and its errors. */
    private val AppDirs.realRuns: Path get() = checkNotNull(temp.parent).realPath().assertSuccess()

    /** A run's temp folder in [realRuns], its own name never followed. */
    private val AppDirs.realTemp: Path get() = realRuns / temp.name

    /** The lock file beside a run's temp folder that claims it. */
    private val AppDirs.claim: Path get() = checkNotNull(temp.parent) / ".${temp.name}.lock"

    /** What a run that was killed leaves: its folder, and a lock file nobody holds. */
    private fun AppDirs.leftBehind(): AppDirs = also {
        createDirectoriesRaw(temp, OWNER_ONLY_FOLDER)
        claim.writeRaw("")
    }

    private fun returnOutOf(app: AppDirs): String {
        val _ = app.withRunTemp { temp ->
            temp.writeText("a.txt", "a").assertSuccess()
            return "left early"
        }
        return "ran to the end"
    }

    @Test
    fun `a return out of the block clears the folder and its claim`() {
        val app = run()

        assertEquals("left early", returnOutOf(app))

        assertFalse(SystemFileSystem.exists(app.temp))
        assertFalse(SystemFileSystem.exists(app.claim))
    }

    @Test
    fun `withRunTemp hands a private writer on the run's temp folder and gives back what the block returned`() {
        val app = run()

        val outcome = app.withRunTemp { temp ->
            assertEquals(app.realTemp, temp.root)
            temp.isPrivate && temp.root.exists()
        }

        assertEquals(Ok(true), outcome)
    }

    @Test
    fun `withRunTemp clears its folder and its lock file when the block returns`() {
        val app = run()

        app.withRunTemp { temp ->
            temp.writeText("work/a.txt", "a").assertSuccess()
        }.assertSuccess()

        assertFalse(app.temp.exists())
        assertFalse(app.claim.exists())
    }

    @Test
    fun `withRunTemp answers what the block returned when the block removed the folder itself`() {
        val app = run()

        val outcome = app.withRunTemp { temp ->
            temp.root.deleteRecursively().assertSuccess()
            "done"
        }

        assertEquals(Ok("done"), outcome)
        assertFalse(app.claim.exists())
    }

    @Test
    fun `withRunTemp clears its folder and its lock file when the block throws`() {
        val app = run()

        assertFailsWith<IllegalStateException> {
            app.withRunTemp { temp ->
                temp.writeText("a.txt", "a").assertSuccess()
                error("the run failed")
            }
        }

        assertFalse(app.temp.exists())
        assertFalse(app.claim.exists())
    }

    @Test
    fun `a run that left its folder behind is stale and a running one is not`() {
        val crashed = run().leftBehind()
        val running = run()
        val doctor = run()

        val stale = running.withRunTemp { doctor.withRunTemp { doctor.staleTempRuns() } }

        assertEquals(Ok(Ok(Ok(StaleRuns(listOf(crashed.realTemp), failed = emptyList())))), stale)
    }

    @Test
    fun `a run holding its lock is not stale before or after its folder appears`() {
        val starting = run()
        val doctor = run()
        createDirectoriesRaw(checkNotNull(starting.temp.parent))

        starting.claim.tryLock().assertSuccess().use {
            assertEquals(Ok(NONE_STALE), doctor.staleTempRuns())
            createDirectoriesRaw(starting.temp)
            assertEquals(Ok(NONE_STALE), doctor.staleTempRuns())
        }
    }

    @Test
    fun `a folder whose lock nobody holds is stale and its lock file is not listed`() {
        val crashed = run().leftBehind()

        assertEquals(Ok(StaleRuns(listOf(crashed.realTemp), failed = emptyList())), run().staleTempRuns())
    }

    @Test
    fun `a run never lists its own folder`() {
        val app = run().leftBehind()

        assertEquals(Ok(NONE_STALE), app.staleTempRuns())
    }

    @Test
    fun `an app with no temp folders yet has no stale runs`() {
        assertEquals(Ok(NONE_STALE), run().staleTempRuns())
    }

    @Test
    fun `clearStaleTempRuns with no temp folders yet clears nothing`() {
        assertEquals(Ok(StaleCleanup(emptyList(), emptyList())), run().clearStaleTempRuns())
    }

    @Test
    fun `clearStaleTempRuns removes a crashed run's folder and its lock file`() {
        val crashed = run().leftBehind()
        (crashed.temp / "a.txt").writeRaw("a")

        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), run().clearStaleTempRuns())
        assertFalse(crashed.temp.exists())
        assertFalse(crashed.claim.exists())
    }

    @Test
    fun `clearStaleTempRuns removes entries whose names a writer would refuse`() {
        val crashed = run().leftBehind()
        val made = runCatching {
            (crashed.temp / "a\\b").writeRaw("a")
            createDirectoriesRaw(crashed.temp / "C:x")
        }
        // Windows refuses both names, which leaves nothing to observe.
        if (made.isFailure) return
        (crashed.temp / " ").writeRaw("b")
        (crashed.temp / "C:x" / "c.txt").writeRaw("c")

        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), run().clearStaleTempRuns())
        assertFalse(crashed.temp.exists())
        assertFalse(crashed.claim.exists())
    }

    @Test
    fun `clearStaleTempRuns clears a stale run folder others may read`() {
        val crashed = run()
        createDirectoriesRaw(crashed.temp, 0b111_101_101)
        crashed.claim.writeRaw("")
        (crashed.temp / "a.txt").writeRaw("a")

        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), run().clearStaleTempRuns())
        assertFalse(crashed.temp.exists())
        assertFalse(crashed.claim.exists())
    }

    @Test
    fun `clearing a stale run folder that is gone is NotFound and makes nothing`() {
        val gone = run()
        createDirectoriesRaw(checkNotNull(gone.temp.parent))

        assertEquals(Err(FileError.NotFound(gone.realTemp)), gone.realTemp.clearStaleRun())
        assertFalse(gone.temp.exists())
    }

    @Test
    fun `clearStaleTempRuns leaves a live run its folder and its lock file`() {
        val running = run()
        val doctor = run()

        val outcome = running.withRunTemp {
            val cleared = doctor.clearStaleTempRuns()
            Triple(cleared, running.temp.exists(), running.claim.exists())
        }

        assertEquals(Ok(Triple(Ok(StaleCleanup(emptyList(), emptyList())), true, true)), outcome)
    }

    /** What a run that ends removes: its folder, then its lock file. */
    private fun AppDirs.finish() {
        temp.deleteTree()
        SystemFileSystem.delete(claim)
    }

    @Test
    fun `a run that ends between the listing and its claim is neither stale nor failed and leaves no lock file`() {
        val finishing = run().leftBehind()

        assertEquals(Ok(NONE_STALE), run().staleTempRuns(beforeClaim = { finishing.finish() }))
        assertFalse(finishing.claim.exists())
    }

    @Test
    fun `a run that ends between the listing and its claim is neither cleared nor failed and leaves no lock file`() {
        val finishing = run().leftBehind()

        assertEquals(Ok(NONE_CLEARED), run().clearStaleTempRuns(beforeClaim = { finishing.finish() }))
        assertFalse(finishing.claim.exists())
    }

    @Test
    fun `of two cleanups that list one stale run only the one that clears it reports it`() {
        val crashed = run().leftBehind()
        val second = run()
        var secondCleared: Result<StaleCleanup, FileError>? = null

        val first = run().clearStaleTempRuns(beforeClaim = { secondCleared = second.clearStaleTempRuns() })

        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), secondCleared)
        assertEquals(Ok(NONE_CLEARED), first)
        assertFalse(crashed.temp.exists())
        assertFalse(crashed.claim.exists())
    }

    @Test
    fun `clearing a stale run whose lock file is already gone clears its folder`() {
        val crashed = run()
        createDirectoriesRaw(crashed.temp, OWNER_ONLY_FOLDER)

        assertEquals(Ok(Unit), crashed.realTemp.clearStaleRun())
        assertFalse(crashed.temp.exists())
    }

    @Test
    fun `clearStaleTempRuns leaves a lock file whose folder is already gone`() {
        val orphan = run()
        createDirectoriesRaw(checkNotNull(orphan.temp.parent))
        orphan.claim.writeRaw("")

        assertEquals(Ok(StaleCleanup(emptyList(), emptyList())), run().clearStaleTempRuns())
        assertTrue(orphan.claim.exists())
    }

    @Test
    fun `a folder or file not named like a run id is not a stale run`() {
        val crashed = run().leftBehind()
        val runs = checkNotNull(crashed.temp.parent)
        createDirectoriesRaw(runs / "not-a-uuid")
        createDirectoriesRaw(runs / Uuid.random().toString().uppercase())
        (runs / "notes.txt").writeRaw("notes")

        assertEquals(Ok(StaleRuns(listOf(crashed.realTemp), failed = emptyList())), run().staleTempRuns())
    }

    @Test
    fun `clearStaleTempRuns leaves a folder or file not named like a run id`() {
        val crashed = run().leftBehind()
        val runs = checkNotNull(crashed.temp.parent)
        val folder = runs / "not-a-uuid"
        createDirectoriesRaw(folder)
        val kept = (folder / "kept.txt").writeRaw("kept")
        val file = (runs / "notes.txt").writeRaw("notes")

        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), run().clearStaleTempRuns())
        assertFalse(crashed.temp.exists())
        assertEquals("kept", kept.readRaw())
        assertEquals("notes", file.readRaw())
        assertFalse((runs / ".not-a-uuid.lock").exists())
    }

    /** A symlink named like a run id in the folder holding the runs, to a folder `victim/` outside it with a file. */
    private fun AppDirs.runNamedLinkToVictim(): Pair<Path, Path> {
        val runs = checkNotNull(temp.parent)
        createDirectoriesRaw(runs)
        val victim = dir / "victim"
        createDirectoriesRaw(victim)
        val file = (victim / "kept.txt").writeRaw("kept")
        val link = runs / Uuid.random().toString()
        link.createSymlink(victim).assertSuccess()
        return link to file
    }

    @Test
    fun `a symlink named like a run id is not a stale run`() {
        val app = run()
        val (link, file) = app.runNamedLinkToVictim()

        assertEquals(Ok(NONE_STALE), app.staleTempRuns())
        assertEquals(Ok(true), link.isSymlink())
        assertEquals("kept", file.readRaw())
    }

    @Test
    fun `clearStaleTempRuns leaves a symlink named like a run id and the folder it leads to`() {
        val app = run()
        val (link, file) = app.runNamedLinkToVictim()

        assertEquals(Ok(NONE_CLEARED), app.clearStaleTempRuns())
        assertEquals(Ok(true), link.isSymlink())
        assertEquals("kept", file.readRaw())
        assertFalse((checkNotNull(link.parent) / ".${link.name}.lock").exists())
    }

    @Test
    fun `a second claim on one run id is RunTempClaimed and leaves the first run its folder and lock`() {
        val runId = Uuid.random()
        val first = base.forApp("app", runId).assertSuccess()
        val second = base.forApp("app", runId).assertSuccess()

        val outcome = first.withRunTemp {
            val claimed = Err(FileError.RunTempClaimed(second.realTemp, currentProcessId()))
            assertEquals(claimed, second.withRunTemp { })
            first.temp.exists() && first.claim.exists()
        }

        assertEquals(Ok(true), outcome)
    }

    @Test
    fun `a second withRunTemp on one AppDirs in this process is RunTempClaimed at once naming this process`() {
        val app = run()

        val inner = app.withRunTemp { app.withRunTemp { } }

        assertEquals(Ok(Err(FileError.RunTempClaimed(app.realTemp, currentProcessId()))), inner)
    }

    @Test
    fun `a folder at a run's claim path is NotRegularFile and creates no run folder`() {
        val app = run()
        createDirectoriesRaw(app.claim)

        assertEquals(Err(FileError.NotRegularFile(app.claim, FileType.Directory)), app.withRunTemp { })
        assertFalse(app.temp.exists())
    }

    @Test
    fun `a claim that is busy is RunTempClaimed and creates no folder`() {
        val app = run()
        createDirectoriesRaw(checkNotNull(app.temp.parent))

        app.claim.tryLock().assertSuccess().use {
            assertEquals(Err(FileError.RunTempClaimed(app.realTemp, currentProcessId())), app.withRunTemp { })
        }

        assertFalse(app.temp.exists())
    }

    /** A folder `victim/` outside the folder holding the runs, holding a file, made `755`. */
    private fun victim(): Pair<Path, Path> {
        val victim = dir / "victim"
        createDirectoriesRaw(victim)
        return victim to (victim / "kept.txt").writeRaw("kept")
    }

    @Test
    fun `claiming a run whose path is a symlink is NotADirectory and leaves the folder it leads to alone`() {
        val app = run()
        createDirectoriesRaw(checkNotNull(app.temp.parent))
        val (victim, file) = victim()
        app.temp.createSymlink(victim).assertSuccess()

        val outcome = app.withRunTemp { temp -> temp.writeText("new.txt", "new") }

        assertEquals(Err(FileError.NotADirectory(app.realTemp, FileType.Symlink)), outcome)
        assertEquals(Ok(true), app.temp.isSymlink())
        assertEquals(listOf(file), SystemFileSystem.list(victim))
        assertEquals("kept", file.readRaw())
        victim.assertPermissions(0b111_101_101)
        assertFalse(app.claim.exists())
    }

    @Test
    fun `a stale run swapped for a symlink after the listing is failed and the folder it leads to is kept`() {
        val crashed = run().leftBehind()
        val (victim, file) = victim()
        val swap = { _: Path ->
            crashed.temp.deleteTree()
            crashed.temp.createSymlink(victim).assertSuccess()
        }

        val cleanup = run().clearStaleTempRuns(beforeClaim = swap).assertSuccess()

        assertEquals(emptyList(), cleanup.cleared)
        assertEquals(listOf(crashed.realTemp), cleanup.failed.map { it.folder })
        assertIs<FileError.FolderReplaced>(cleanup.failed.single().error)
        assertEquals(Ok(true), crashed.temp.isSymlink())
        assertEquals("kept", file.readRaw())
    }

    @Test
    fun `a run folder swapped for a symlink while the block runs is FolderReplaced and its target is kept`() {
        val app = run()
        val (victim, file) = victim()

        val outcome = app.withRunTemp {
            app.temp.deleteTree()
            app.temp.createSymlink(victim).assertSuccess()
        }

        assertEquals(Err(FileError.FolderReplaced(app.realTemp)), outcome)
        assertEquals("kept", file.readRaw())
        assertFalse(app.claim.exists())
    }

    @Test
    fun `a run folder swapped for a dangling symlink while the block runs is FolderReplaced and the link stays`() {
        val app = run()

        val outcome = app.withRunTemp {
            app.temp.deleteTree()
            app.temp.createSymlink(dir / "missing").assertSuccess()
        }

        assertEquals(Err(FileError.FolderReplaced(app.realTemp)), outcome)
        assertEquals(Ok(true), app.temp.isSymlink())
        assertFalse(app.claim.exists())
    }

    @Test
    fun `a stale run swapped for a dangling symlink after the listing is FolderReplaced and the link stays`() {
        val crashed = run().leftBehind()
        val swap = { _: Path ->
            crashed.temp.deleteTree()
            crashed.temp.createSymlink(dir / "missing").assertSuccess()
        }

        val cleanup = run().clearStaleTempRuns(beforeClaim = swap)

        val failure = StaleFailure(crashed.realTemp, FileError.FolderReplaced(crashed.realTemp))
        assertEquals(Ok(StaleCleanup(cleared = emptyList(), failed = listOf(failure))), cleanup)
        assertEquals(Ok(true), crashed.temp.isSymlink())
    }

    /**
     * The folder holding the runs' folders made a symlink to a folder `victim/` outside it, which holds a file and a
     * folder named like a run id; answers the victim and what it holds.
     */
    private fun AppDirs.runsLinkedToVictim(): Pair<Path, Set<Path>> {
        val runs = checkNotNull(temp.parent)
        createDirectoriesRaw(checkNotNull(runs.parent))
        val (victim, _) = victim()
        createDirectoriesRaw(victim / Uuid.random().toString(), OWNER_ONLY_FOLDER)
        runs.createSymlink(victim).assertSuccess()
        return victim to SystemFileSystem.list(victim).toSet()
    }

    /** What every run call answers for a symlink at the folder holding the runs' folders: its real place. */
    private val AppDirs.linkedRuns: Err<FileError>
        get() {
            val runs = checkNotNull(temp.parent)
            val place = checkNotNull(runs.parent).realPath().assertSuccess() / runs.name
            return Err(FileError.NotADirectory(place, FileType.Symlink))
        }

    @Test
    fun `withRunTemp refuses a symlink at the folder holding the runs and makes nothing where it leads`() {
        val app = run()
        val (victim, held) = app.runsLinkedToVictim()

        assertEquals(app.linkedRuns, app.withRunTemp { })
        assertEquals(held, SystemFileSystem.list(victim).toSet())
    }

    @Test
    fun `staleTempRuns refuses a symlink at the folder holding the runs and makes nothing where it leads`() {
        val app = run()
        val (victim, held) = app.runsLinkedToVictim()

        assertEquals(app.linkedRuns, app.staleTempRuns())
        assertEquals(held, SystemFileSystem.list(victim).toSet())
    }

    @Test
    fun `clearStaleTempRuns refuses a symlink at the folder holding the runs and clears nothing where it leads`() {
        val app = run()
        val (victim, held) = app.runsLinkedToVictim()

        assertEquals(app.linkedRuns, app.clearStaleTempRuns())
        assertEquals(held, SystemFileSystem.list(victim).toSet())
    }

    /** These base folders with the temp folder reached through a symlink `linked-tmp` to `real-tmp`. */
    private fun linkedTempBase(): BaseDirs {
        val real = dir / "real-tmp"
        createDirectoriesRaw(real)
        val linked = (dir / "linked-tmp").also { it.createSymlink(real).assertSuccess() }
        return base.copy(tempHome = linked)
    }

    @Test
    fun `a symlink above the folder holding the runs is followed and every run call names real paths`() {
        val linkedBase = linkedTempBase()
        val crashed = linkedBase.forApp("app", Uuid.random()).assertSuccess().leftBehind()
        val app = linkedBase.forApp("app", Uuid.random()).assertSuccess()

        assertEquals(Ok(StaleRuns(listOf(crashed.realTemp), failed = emptyList())), app.staleTempRuns())
        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), app.clearStaleTempRuns())
        assertEquals(Ok(app.realTemp), app.withRunTemp { temp -> temp.root })
    }

    @Test
    fun `a crashed run's temp folder is listed by the real path its next run with that id is rooted at`() {
        val linkedBase = linkedTempBase()
        val runId = Uuid.random()
        val crashed = linkedBase.forApp("app", runId).assertSuccess().leftBehind()

        val listed = linkedBase.forApp("app", Uuid.random()).assertSuccess().staleTempRuns().assertSuccess()
        val root = crashed.withRunTemp { temp -> temp.root }

        assertEquals(Ok(listed.stale.single()), root)
    }

    @Test
    fun `a stale run failure behind a linked temp folder names the same real path as its error`() {
        val linkedBase = linkedTempBase()
        val crashed = linkedBase.forApp("app", Uuid.random()).assertSuccess().leftBehind()
        val doctor = linkedBase.forApp("app", Uuid.random()).assertSuccess()
        val swap = { _: Path ->
            crashed.temp.deleteTree()
            crashed.temp.createSymlink(dir / "missing").assertSuccess()
        }

        val failure = doctor.clearStaleTempRuns(beforeClaim = swap).assertSuccess().failed.single()

        assertEquals(crashed.realTemp, failure.folder)
        assertEquals(failure.folder, failure.error.path)
    }

    @Test
    fun `a claimed run behind a linked temp folder is RunTempClaimed naming its real path`() {
        val runId = Uuid.random()
        val linkedBase = linkedTempBase()
        val first = linkedBase.forApp("app", runId).assertSuccess()
        val second = linkedBase.forApp("app", runId).assertSuccess()

        val inner = first.withRunTemp { second.withRunTemp { } }

        assertEquals(Ok(Err(FileError.RunTempClaimed(second.realTemp, currentProcessId()))), inner)
    }

    @Test
    fun `a symlink at the folder holding the runs behind a linked temp folder is named by its real place`() {
        val app = linkedTempBase().forApp("app", Uuid.random()).assertSuccess()
        val (victim, held) = app.runsLinkedToVictim()

        assertEquals(app.linkedRuns, app.staleTempRuns())
        assertEquals(held, SystemFileSystem.list(victim).toSet())
    }

    /** The folder holding the runs' folders made `777`, its info, or null on Windows, which keeps no permissions. */
    private fun AppDirs.runsAnybodyMayWrite(): FileInfo? {
        val runs = checkNotNull(temp.parent)
        createDirectoriesRaw(runs)
        runs.chmod(0b111_111_111).assertSuccess()
        return runs.info().assertSuccess().takeIf { it.permissions != null }
    }

    /** What every run call answers for a folder holding the runs' folders that anybody may write. */
    private fun AppDirs.refusedRuns(info: FileInfo): Err<FileError> {
        val runs = checkNotNull(temp.parent)
        return Err(FileError.Refused(realRuns, info, RefusedBecause.OthersMayWrite, runs))
    }

    @Test
    fun `withRunTemp refuses a folder holding the runs that anybody may write and makes nothing in it`() {
        val app = run()
        val info = app.runsAnybodyMayWrite() ?: return

        assertEquals(app.refusedRuns(info), app.withRunTemp { })
        assertEquals(emptyList(), SystemFileSystem.list(checkNotNull(app.temp.parent)))
    }

    @Test
    fun `staleTempRuns refuses a folder holding the runs that anybody may write and makes nothing in it`() {
        val crashed = run().leftBehind()
        val app = run()
        val info = app.runsAnybodyMayWrite() ?: return
        val before = SystemFileSystem.list(checkNotNull(app.temp.parent)).toSet()

        assertEquals(app.refusedRuns(info), app.staleTempRuns())
        assertEquals(before, SystemFileSystem.list(checkNotNull(app.temp.parent)).toSet())
        assertTrue(crashed.temp.exists())
    }

    @Test
    fun `clearStaleTempRuns refuses a folder holding the runs that anybody may write and clears nothing`() {
        val crashed = run().leftBehind()
        (crashed.temp / "a.txt").writeRaw("a")
        val app = run()
        val info = app.runsAnybodyMayWrite() ?: return
        val before = SystemFileSystem.list(checkNotNull(app.temp.parent)).toSet()

        assertEquals(app.refusedRuns(info), app.clearStaleTempRuns())
        assertEquals(before, SystemFileSystem.list(checkNotNull(app.temp.parent)).toSet())
        assertEquals("a", (crashed.temp / "a.txt").readRaw())
    }

    @Test
    fun `a folder holding the runs that is yours and others may only read takes every run call`() {
        val crashed = run().leftBehind()
        val app = run()
        checkNotNull(app.temp.parent).chmod(0b111_101_101).assertSuccess()

        assertEquals(Ok(StaleRuns(listOf(crashed.realTemp), failed = emptyList())), app.staleTempRuns())
        assertEquals(Ok(StaleCleanup(listOf(crashed.realTemp), failed = emptyList())), app.clearStaleTempRuns())
        assertEquals(Ok(true), app.withRunTemp { app.temp.exists() })
    }

    @Test
    fun `a run folder that was already there stays when it cannot be made owner-only`() {
        val folder = dir / "run"
        createDirectoriesRaw(folder)
        val kept = (folder / "kept.txt").writeRaw("kept")

        val failure = folder.createPrivateDirectory(restrict = { throw IOException("refused") })

        failure.assertError<FileError.WriteFailed>()
        assertEquals("kept", kept.readRaw())
    }

    @Test
    fun `a run folder this call created is removed again when it cannot be made owner-only`() {
        val folder = dir / "run"

        val failure = folder.createPrivateDirectory(restrict = { throw IOException("refused") })

        failure.assertError<FileError.WriteFailed>()
        assertFalse(folder.exists())
    }
}

private val NONE_STALE = StaleRuns(stale = emptyList(), failed = emptyList())
private val NONE_CLEARED = StaleCleanup(cleared = emptyList(), failed = emptyList())
