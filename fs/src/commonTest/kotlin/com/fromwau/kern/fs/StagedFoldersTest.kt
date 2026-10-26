package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class StagedFoldersTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @Test
    fun `a built folder appears whole at its path and leaves no stage`() {
        val target = dir / "pkg"

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            (stage / "sub").createDirectories().assertSuccess()
            42
        }

        assertEquals(Ok(42), built)
        assertEquals("a", (target / "a.txt").readRaw())
        assertEquals(Ok(FileType.Directory), (target / "sub").fileType())
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a folder that exists is AlreadyExists and build never runs`() {
        val target = dir / "pkg"
        createDirectoriesRaw(target)
        var ran = false

        val exists = Err(FileError.AlreadyExists(target, FileType.Directory))
        assertEquals(exists, target.createDirectoryAtomically { ran = true })

        assertFalse(ran)
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a file or a dangling link at the path is AlreadyExists too`() {
        val file = (dir / "file").writeRaw("f")
        val link = links.make(dir / "link", dir / "missing")

        assertEquals(Err(FileError.AlreadyExists(file, FileType.Regular)), file.createDirectoryAtomically { })
        assertEquals(Err(FileError.AlreadyExists(link, FileType.Symlink)), link.createDirectoryAtomically { })
    }

    @Test
    fun `a folder that appears while build runs is AlreadyExists and the stage is removed`() {
        val target = dir / "pkg"

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("mine")
            createDirectoriesRaw(target)
        }

        assertEquals(Err(FileError.AlreadyExists(target, FileType.Directory)), built)
        assertEquals(emptyList(), SystemFileSystem.list(target))
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `what build throws is rethrown and the stage is removed`() {
        val target = dir / "pkg"

        assertFailsWith<IllegalStateException> {
            target.createDirectoryAtomically { stage ->
                (stage / "a.txt").writeRaw("a")
                error("cut short")
            }
        }

        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    private fun buildThenReturn(target: Path): String {
        val _ = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            return "left early"
        }
        return "built"
    }

    @Test
    fun `a return out of build removes the stage`() {
        assertEquals("left early", buildThenReturn(dir / "pkg"))

        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    @Test
    fun `a labeled return of an Err from build places nothing and answers that Err`() {
        val target = dir / "pkg"
        val failure = Err(FileError.NotFound(dir / "missing"))

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            return@createDirectoryAtomically failure
        }

        assertEquals(Ok(failure), built)
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    @Test
    fun `an Ok from build puts the folder in place`() {
        val target = dir / "pkg"

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            return@createDirectoryAtomically Ok("done")
        }

        assertEquals(Ok(Ok("done")), built)
        assertEquals("a", (target / "a.txt").readRaw())
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a labeled return of a plain value from build is its answer and puts the folder in place`() {
        val target = dir / "pkg"

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            return@createDirectoryAtomically "left early"
        }

        assertEquals(Ok("left early"), built)
        assertEquals("a", (target / "a.txt").readRaw())
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a name whose stage claim would pass 255 bytes is NameTooLong for the stage`() {
        val fits = dir / "f".repeat(212)
        val target = dir / "x".repeat(213)

        assertEquals(Ok(Unit), fits.createDirectoryAtomically { })
        assertEquals(Err(FileError.NameTooLong(target, LongName.Stage, 256)), target.createDirectoryAtomically { })
        assertEquals(listOf(fits), SystemFileSystem.list(dir))
    }

    @Test
    fun `a stage left by a killed build is a StagedFolder that a writer discards whole`() {
        val stage = dir / ".pkg.$A_WRITE_ID.dir"
        createDirectoriesRaw(stage / "sub")
        (stage / "sub" / "a.txt").writeRaw("a")
        val expected = Leftover.StagedFolder(file = dir / "pkg", copy = stage, startedAt = A_WRITE_START)

        assertEquals(Ok(LeftoverScan(listOf(expected), emptyList(), emptyList(), emptyList())), dir.leftoversIn())
        assertEquals(Ok(listOf<Leftover>(expected)), (dir / "pkg").leftovers())
        assertEquals(Ok(Unit), dir.writer().assertSuccess().discard(expected))
        assertEquals(emptyList(), SystemFileSystem.list(dir))
        assertEquals(Err(FileError.NotFound(stage)), dir.writer().assertSuccess().discard(expected))
    }

    @Test
    fun `discarding the stage of a build still running is LockBusy and the stage keeps its files`() {
        val target = dir / "pkg"

        val built = target.createDirectoryAtomically { stage ->
            (stage / "a.txt").writeRaw("a")
            val leftover = dir.leftoversIn().assertSuccess().found.single()
            val claim = dir / "${stage.name.removeSuffix(".dir")}.lock"

            val discarded = dir.writer().assertSuccess().discard(leftover)

            assertEquals(Err(FileError.LockBusy(claim, currentProcessId(), Duration.ZERO)), discarded)
            assertEquals("a", (stage / "a.txt").readRaw())
        }

        assertEquals(Ok(Unit), built)
        assertEquals("a", (target / "a.txt").readRaw())
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a stage and its claim left by a killed build are one StagedFolder that a writer discards both of`() {
        val stage = dir / ".pkg.$A_WRITE_ID.dir"
        createDirectoriesRaw(stage)
        (stage / "a.txt").writeRaw("a")
        (dir / ".pkg.$A_WRITE_ID.lock").writeRaw("")
        val expected = Leftover.StagedFolder(file = dir / "pkg", copy = stage, startedAt = A_WRITE_START)

        assertEquals(Ok(LeftoverScan(listOf(expected), emptyList(), emptyList(), emptyList())), dir.leftoversIn())
        assertEquals(Ok(Unit), dir.writer().assertSuccess().discard(expected))
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }

    @Test
    fun `a scan with unlimited depth lists a stage once and does not look inside it`() {
        val stage = dir / ".pkg.$A_WRITE_ID.dir"
        createDirectoriesRaw(stage)
        (stage / ".a.txt.$A_WRITE_ID.tmp").writeRaw("a")
        val staged = Leftover.StagedFolder(file = dir / "pkg", copy = stage, startedAt = A_WRITE_START)

        assertEquals(listOf<Leftover>(staged), dir.leftoversIn(depth = Int.MAX_VALUE).assertSuccess().found)
    }

    @Test
    fun `a link named like a stage is listed as a StagedFolder and a writer discards only the link`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        (outside / "x.txt").writeRaw("x")
        val scanned = dir / "scanned"
        createDirectoriesRaw(scanned)
        val link = links.make(scanned / ".pkg.$A_WRITE_ID.dir", outside)
        val expected = Leftover.StagedFolder(file = scanned / "pkg", copy = link, startedAt = A_WRITE_START)

        assertEquals(listOf<Leftover>(expected), scanned.leftoversIn().assertSuccess().found)
        assertEquals(Ok(Unit), scanned.writer().assertSuccess().discard(expected))
        assertEquals(emptyList(), SystemFileSystem.list(scanned))
        assertEquals("x", (outside / "x.txt").readRaw())
    }
}
