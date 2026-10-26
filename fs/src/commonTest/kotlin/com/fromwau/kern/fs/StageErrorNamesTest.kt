package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

/** What a stage's writer names in the errors it answers, which is each path as it will be once the stage is placed. */
class StageErrorNamesTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val other = (dir / "other").also { createDirectoriesRaw(it) }
    private val links = TestLinks()
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()
    private val placed = root / "pkg"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** What [block] answers inside the stage of `pkg`, with that stage's root. */
    private fun <T> inStage(block: (stage: Writer) -> T): T =
        writer.createDirectoryAtomically("pkg") { stage -> block(stage) }.assertSuccess()

    @Test
    fun `a leftovers scan in a stage names a folder it could not list by the placed path`() {
        val failed = inStage { stage ->
            stage.createDirectories("lib").assertSuccess()
            val lib = stage.root / "lib"
            var scanned: LeftoverScan? = null
            lib.whileUnlistable {
                scanned = stage.leftovers(depth = 1).assertSuccess()
            }
            scanned?.failed
        } ?: return

        assertEquals(listOf(placed / "lib"), failed.map { it.path })
        assertEquals(listOf(placed / "lib"), failed.map { it.error.path })
    }

    @Test
    fun `a stage discard of a copy outside it names the placed folder as the boundary`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val backup = Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = null)

        val discarded = inStage { stage -> stage.discard(backup) }

        val resolved = other.realPath().assertSuccess()
        assertEquals(Err(FileError.Escapes(copy, boundary = placed, resolved = resolved)), discarded)
    }

    @Test
    fun `a stage discard through a link that leads elsewhere names the placed folder as the boundary`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val unrelated = (dir / "unrelated").also { createDirectoriesRaw(it) }

        val discarded = inStage { stage ->
            val link = links.make(stage.root / "conf", unrelated)
            stage.discard(Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = link), throughLinks = true)
        }

        val resolved = other.realPath().assertSuccess()
        assertEquals(Err(FileError.Escapes(copy, boundary = placed, resolved = resolved)), discarded)
        assertEquals("a = 1", copy.readRaw())
    }

    @Test
    fun `an append file a stage opened names the placed path once closed`() {
        val appended = inStage { stage ->
            val log = stage.openAppend("app.log").assertSuccess()
            log.close()
            log.append("late".encodeToByteArray())
        }

        assertEquals(Err(FileError.Closed(placed / "app.log")), appended)
    }

    @Test
    fun `an append file a stage opened to reopen names the placed path once closed`() {
        val appended = inStage { stage ->
            val log = stage.openAppend("app.log", Reopen.WhenMoved()).assertSuccess()
            log.close()
            log.append("late".encodeToByteArray())
        }

        assertEquals(Err(FileError.Closed(placed / "app.log")), appended)
    }

    @Test
    fun `a lock a stage took names the placed path once its file is gone`() {
        val checked = inStage { stage ->
            stage.tryLock("build.lock").assertSuccess().use { lock ->
                stage.delete("build.lock").assertSuccess()
                lock.check()
            }
        }

        assertEquals(Err(FileError.LockLost(placed / "build.lock")), checked)
    }

    @Test
    fun `a stage in a stage names each path as placed in both`() {
        val refused = inStage { stage ->
            stage.createDirectoryAtomically("lib") { inner -> inner.writeText("../x", "y") }.assertSuccess()
        }

        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)
        assertEquals(Err(FileError.InvalidRelativePath(placed / "lib", "../x", problem)), refused)
    }

    @Test
    fun `a stage written to through a link in the root names the placed folder by its real path`() {
        createDirectoriesRaw(rootPath / "releases" / "43")
        links.make(rootPath / "current", Path("releases/43"))

        val refused = writer.createDirectoryAtomically("current/pkg") { stage -> stage.writeText("../x", "y") }

        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)
        val folder = root / "releases" / "43" / "pkg"
        assertEquals(Ok(Err(FileError.InvalidRelativePath(folder, "../x", problem))), refused)
    }
}
