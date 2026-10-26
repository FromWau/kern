package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path

class MirrorUncleanTest {
    private val dir = newTempDir()
    private val links = TestLinks()
    private val root = (dir / "root").also { createDirectoriesRaw(it) }
    private val dst = dir / "dst"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun out(): Writer = dst.writer().assertSuccess()

    @Test
    fun `a run that did all it planned has nothing unclean and is clean`() {
        (root / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.unclean)
        assertTrue(report.isClean)
    }

    @Test
    fun `a file that could not be copied is unclean as Failed`() {
        (root / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out(), copy = { _, _, name -> Err(FileError.NotFound(dst / name)) }).assertSuccess()

        assertEquals(report.failures.map { MirrorReport.Unclean.Failed(it) }, report.unclean)
        assertEquals(1, report.unclean.size)
        assertFalse(report.isClean)
    }

    @Test
    fun `a stale destination folder that could not be read is unclean as NotRemoved`() {
        (root / "a.txt").writeRaw("a")
        val old = (dst / "old").also { createDirectoriesRaw(it) }
        (old / "x.txt").writeRaw("x")

        old.whileUnlistable {
            val report = root.mirrorTo(out()).assertSuccess()

            assertEquals(report.notRemoved.map { MirrorReport.Unclean.NotRemoved(it) }, report.unclean)
            assertEquals(listOf(old), report.notRemoved.map { it.path })
            assertFalse(report.isClean)
        }
    }

    @Test
    fun `a removal approveRemovals declined is unclean as Kept`() {
        (root / "a.txt").writeRaw("a")
        createDirectoriesRaw(dst)
        (dst / "stale.txt").writeRaw("s")

        val report = root.mirrorTo(out(), approveRemovals = { emptyList() }).assertSuccess()

        val kept = Removal(Path("stale.txt"), RemovalReason.Stale)
        assertEquals(listOf(MirrorReport.Unclean.Kept(kept)), report.unclean)
        assertFalse(report.isClean)
    }

    @Test
    fun `the copy at a link leading out that the default keeps is unclean as Kept at the passed over place`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        (outside / "secret.txt").writeRaw("s")
        createDirectoriesRaw(dst)
        (dst / "leak.txt").writeRaw("old copy")
        links.make(root / "leak.txt", outside / "secret.txt")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        val atLeak = RemovalReason.AtPassedOver(WalkSkip.LeadsOutside, place = Path("leak.txt"))
        assertEquals(listOf(MirrorReport.Unclean.Kept(Removal(Path("leak.txt"), atLeak))), report.unclean)
        assertFalse(report.isClean)
    }

    @Test
    fun `a dangling link held under Inside is unclean as Held`() {
        (root / "a.txt").writeRaw("a")
        links.make(root / "gone.txt", dir / "missing")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        val held = WalkEntry.Skipped(root / "gone.txt", Path("gone.txt"), WalkSkip.DanglingLink)
        assertEquals(listOf(held), report.held)
        assertEquals(listOf(MirrorReport.Unclean.Held(held)), report.unclean)
        assertFalse(report.isClean)
    }

    @Test
    fun `entries held because exclude or enter asked for it are not unclean`() {
        createDirectoriesRaw(root / ".git")
        (root / "a.txt").writeRaw("a")
        (root / "notes.txt").writeRaw("n")

        val report = root.mirrorTo(
            out(),
            exclude = setOf(root / "notes.txt"),
            enter = { it.path.name != ".git" },
        ).assertSuccess()

        assertEquals(listOf(WalkSkip.Refused, WalkSkip.Excluded), report.held.map { it.why })
        assertEquals(emptyList(), report.unclean)
        assertTrue(report.isClean)
    }

    @Test
    fun `a link leading out that AsLinks did not make is unclean as LinkNotMade`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        (root / "a.txt").writeRaw("a")
        links.make(root / "absolute", outside)

        val report = root.mirrorTo(out()).assertSuccess()

        val notMade = WalkEntry.Skipped(root / "absolute", Path("absolute"), WalkSkip.LeadsOutside)
        assertEquals(listOf(notMade), report.passedOver)
        assertEquals(listOf(MirrorReport.Unclean.LinkNotMade(notMade)), report.unclean)
        assertFalse(report.isClean)
    }

    @Test
    fun `a link leading out passed over under Inside is not unclean`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        (root / "a.txt").writeRaw("a")
        links.make(root / "absolute", outside)

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        assertEquals(listOf(WalkSkip.LeadsOutside), report.passedOver.map { it.why })
        assertEquals(emptyList(), report.unclean)
        assertTrue(report.isClean)
    }

    @Test
    fun `sourceEmpty says whether the walk of the source yielded no file or folder`() {
        val empty = root.mirrorTo(out()).assertSuccess()
        (root / "a.txt").writeRaw("a")
        val full = root.mirrorTo(out()).assertSuccess()

        assertTrue(empty.sourceEmpty)
        assertFalse(full.sourceEmpty)
    }
}
