package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class MirrorRemovalReasonTest {
    private val dir = newTempDir()
    private val links = TestLinks()
    private val root = (dir / "root").also { createDirectoriesRaw(it) }
    private val dst = dir / "dst"
    private val plans = mutableListOf<RemovalPlan>()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun out(): Writer = dst.writer().assertSuccess()

    /** An approveRemovals that records each plan it is asked about and answers what [answer] picks from it. */
    private fun recording(answer: (RemovalPlan) -> List<Removal>): (RemovalPlan) -> List<Removal> = { plan ->
        plans += plan
        answer(plan)
    }

    /** Why each part of the old copy of `photos` is removed once `photos` is a link leading out. */
    private val atPhotos = RemovalReason.AtPassedOver(WalkSkip.LeadsOutside, place = Path("photos"))

    /** `index.html` and `photos/a.jpg` mirrored once, then the source's `photos` replaced by a link leading out. */
    private fun photosNowALinkOut() {
        (root / "index.html").writeRaw("i")
        createDirectoriesRaw(root / "photos")
        (root / "photos" / "a.jpg").writeRaw("a")
        root.mirrorTo(out()).assertSuccess()
        SystemFileSystem.delete(root / "photos" / "a.jpg")
        SystemFileSystem.delete(root / "photos")
        links.make(root / "photos", dir / "share" / "photos")
    }

    @Test
    fun `by default a source folder now a link leading out removes nothing and lists the removals declined`() {
        photosNowALinkOut()

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.removed)
        val declined = listOf(Removal(Path("photos", "a.jpg"), atPhotos), Removal(Path("photos"), atPhotos))
        assertEquals(declined, report.removalsDeclined)
        assertEquals(mapOf("index.html" to "i", "photos" to "dir", "photos/a.jpg" to "a"), dst.tree())
        assertFalse(report.isClean)
    }

    @Test
    fun `a source folder now a link leading out plans its old copy as removals at a passed over entry`() {
        photosNowALinkOut()

        val report = root.mirrorTo(out(), approveRemovals = recording { it.removals }).assertSuccess()

        val removals = listOf(Removal(Path("photos", "a.jpg"), atPhotos), Removal(Path("photos"), atPhotos))
        assertEquals(listOf(RemovalPlan(removals, files = 1, folders = 1, dstEntries = 3, sourceEmpty = false)), plans)
        assertEquals(listOf(Path("photos", "a.jpg"), Path("photos")), report.removed)
        assertEquals(mapOf("index.html" to "i"), dst.tree())
    }

    @Test
    fun `by default under Inside the old copy at a link to a folder walked under its own path stays`() {
        createDirectoriesRaw(root / "assets")
        (root / "assets" / "x.css").writeRaw("x")
        links.make(root / "alias", Path("assets"))
        createDirectoriesRaw(dst / "alias")
        (dst / "alias" / "x.css").writeRaw("old")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()
        root.mirrorTo(out(), symlinks = Symlinks.Inside, approveRemovals = recording { emptyList() }).assertSuccess()

        val why = RemovalReason.AtPassedOver(WalkSkip.AlreadyEntered(firstRelative = Path("assets")), Path("alias"))
        val removals = listOf(Removal(Path("alias", "x.css"), why), Removal(Path("alias"), why))
        assertEquals(removals, report.removalsDeclined)
        val tree = mapOf("alias" to "dir", "alias/x.css" to "old", "assets" to "dir", "assets/x.css" to "x")
        assertEquals(tree, dst.tree())
        assertFalse(report.isClean)
        assertEquals(removals, plans.single().removals)
    }

    @Test
    fun `by default a FIFO in the source over a file in the destination removes nothing`() {
        (root / "a.txt").writeRaw("a")
        // Windows has no FIFOs, which leaves nothing to observe.
        if ((root / "pipe").mkfifo() !is Result.Success) return
        createDirectoriesRaw(dst)
        (dst / "pipe").writeRaw("old")

        val report = root.mirrorTo(out()).assertSuccess()

        val why = RemovalReason.AtPassedOver(WalkSkip.NotFileOrFolder(FileType.Fifo), place = Path("pipe"))
        assertEquals(listOf(Removal(Path("pipe"), why)), report.removalsDeclined)
        assertEquals(mapOf("a.txt" to "a", "pipe" to "old"), dst.tree())
        assertFalse(report.isClean)
    }

    @Test
    fun `a file in the destination at a FIFO in the source goes once approved and that run is clean`() {
        (root / "a.txt").writeRaw("a")
        if ((root / "pipe").mkfifo() !is Result.Success) return
        createDirectoriesRaw(dst)
        (dst / "pipe").writeRaw("old")

        val report = root.mirrorTo(out(), approveRemovals = recording { it.removals }).assertSuccess()

        val why = RemovalReason.AtPassedOver(WalkSkip.NotFileOrFolder(FileType.Fifo), place = Path("pipe"))
        assertEquals(listOf(Removal(Path("pipe"), why)), plans.single().removals)
        assertEquals(listOf(Path("pipe")), report.removed)
        assertEquals(mapOf("a.txt" to "a"), dst.tree())
        assertTrue(report.isClean, report.toString())
        assertTrue(root.mirrorTo(out()).assertSuccess().isClean)
    }

    @Test
    fun `a stale file is a Stale removal and the default approves it`() {
        (root / "a.txt").writeRaw("a")
        createDirectoriesRaw(dst)
        (dst / "gone.txt").writeRaw("old")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(Path("gone.txt")), report.removed)
        assertTrue(report.isClean, report.toString())
        (dst / "gone.txt").writeRaw("old")
        root.mirrorTo(out(), approveRemovals = recording { it.removals }).assertSuccess()
        assertEquals(listOf(Removal(Path("gone.txt"), RemovalReason.Stale)), plans.single().removals)
    }

    @Test
    fun `a destination folder where the source now has a file is an InTheWay removal and the default approves it`() {
        (root / "x").writeRaw("file")
        createDirectoriesRaw(dst / "x")
        (dst / "x" / "a.txt").writeRaw("old")
        val inTheWay = listOf(
            Removal(Path("x", "a.txt"), RemovalReason.InTheWay),
            Removal(Path("x"), RemovalReason.InTheWay),
        )

        root.mirrorTo(out(), approveRemovals = recording { emptyList() }).assertSuccess()
        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(inTheWay, plans.single().removals)
        assertEquals(listOf(Path("x", "a.txt"), Path("x")), report.removed)
        assertEquals(mapOf("x" to "file"), dst.tree())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `by default a FIFO over a file removes the stale file and keeps what is at the FIFO's place`() {
        (root / "a.txt").writeRaw("a")
        (root / "b.txt").writeRaw("b")
        (root / "old.txt").writeRaw("old")
        root.mirrorTo(out()).assertSuccess()
        SystemFileSystem.delete(root / "old.txt")
        SystemFileSystem.delete(root / "a.txt")
        if ((root / "a.txt").mkfifo() !is Result.Success) return

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(Path("old.txt")), report.removed)
        assertEquals(listOf(Path("a.txt")), report.removalsDeclined.map { it.path })
        assertEquals(mapOf("a.txt" to "a", "b.txt" to "b"), dst.tree())
        assertFalse(report.isClean)
    }

    @Test
    fun `by default a folder now a link leading out keeps its copy while a stale file goes`() {
        (root / "old.txt").writeRaw("old")
        photosNowALinkOut()
        SystemFileSystem.delete(root / "old.txt")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(Path("old.txt")), report.removed)
        assertEquals(listOf(Path("photos", "a.jpg"), Path("photos")), report.removalsDeclined.map { it.path })
        assertEquals(mapOf("index.html" to "i", "photos" to "dir", "photos/a.jpg" to "a"), dst.tree())
        assertFalse(report.isClean)
    }

    @Test
    fun `by default a source that yields nothing removes nothing`() {
        createDirectoriesRaw(dst)
        (dst / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.removed)
        assertEquals(listOf(Removal(Path("a.txt"), RemovalReason.Stale)), report.removalsDeclined)
        assertEquals(mapOf("a.txt" to "a"), dst.tree())
    }

    @Test
    fun `an approver that returns some of the removals has only those carried out`() {
        (root / "a.txt").writeRaw("a")
        createDirectoriesRaw(dst)
        (dst / "x.txt").writeRaw("x")
        (dst / "y.txt").writeRaw("y")

        val report = root
            .mirrorTo(out(), approveRemovals = { plan -> plan.removals.filter { it.path.name == "x.txt" } })
            .assertSuccess()

        assertEquals(listOf(Path("x.txt")), report.removed)
        assertEquals(listOf(Path("y.txt")), report.removalsDeclined.map { it.path })
        assertEquals(mapOf("a.txt" to "a", "y.txt" to "y"), dst.tree())
        assertFalse(report.isClean)
    }

    @Test
    fun `a stale folder holding a declined removal stays with it while the approved removals in it go`() {
        (root / "a.txt").writeRaw("a")
        createDirectoriesRaw(dst / "old")
        (dst / "old" / "x.txt").writeRaw("x")
        (dst / "old" / "y.txt").writeRaw("y")

        val report = root
            .mirrorTo(out(), approveRemovals = { plan -> plan.removals.filter { it.path.name != "y.txt" } })
            .assertSuccess()

        assertEquals(listOf(Path("old", "x.txt")), report.removed)
        assertEquals(listOf(Path("old", "y.txt"), Path("old")), report.removalsDeclined.map { it.path })
        assertEquals(mapOf("a.txt" to "a", "old" to "dir", "old/y.txt" to "y"), dst.tree())
    }

    @Test
    fun `a folder in a file's way with part of its removals approved stays whole and the file is a failure`() {
        (root / "x").writeRaw("file")
        createDirectoriesRaw(dst / "x")
        (dst / "x" / "a.txt").writeRaw("old")

        val report = root.mirrorTo(out(), approveRemovals = { plan -> plan.removals.take(1) }).assertSuccess()

        assertEquals(emptyList(), report.removed)
        assertEquals(listOf(Path("x", "a.txt"), Path("x")), report.removalsDeclined.map { it.path })
        val refused = FileError.NotRegularFile(dst / "x", FileType.Directory)
        assertEquals(listOf(WalkFailure(root / "x", Path("x"), refused)), report.failures)
        assertEquals(mapOf("x" to "dir", "x/a.txt" to "old"), dst.tree())
    }

    @Test
    fun `a removal the approver returns that is not in the plan is ignored`() {
        (root / "a.txt").writeRaw("a")
        createDirectoriesRaw(dst)
        (dst / "gone.txt").writeRaw("old")

        val report = root
            .mirrorTo(out(), approveRemovals = { listOf(Removal(Path("a.txt"), RemovalReason.Stale)) })
            .assertSuccess()

        assertEquals(emptyList(), report.removed)
        assertEquals(listOf(Path("gone.txt")), report.removalsDeclined.map { it.path })
        assertEquals(mapOf("a.txt" to "a", "gone.txt" to "old"), dst.tree())
    }

    @Test
    fun `under Inside the copy of a folder now a link to its twin stays until an approver answers its removals`() {
        createDirectoriesRaw(root / "docs")
        (root / "docs" / "f.txt").writeRaw("f")
        createDirectoriesRaw(root / "docs-v1")
        (root / "docs-v1" / "g.txt").writeRaw("g")
        root.mirrorTo(out()).assertSuccess()
        SystemFileSystem.delete(root / "docs-v1" / "g.txt")
        SystemFileSystem.delete(root / "docs-v1")
        links.make(root / "docs-v1", Path("docs"))

        val kept = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()
        val cleared = root
            .mirrorTo(out(), symlinks = Symlinks.Inside, approveRemovals = { it.removals })
            .assertSuccess()

        assertEquals(listOf(Path("docs-v1", "g.txt"), Path("docs-v1")), kept.removalsDeclined.map { it.path })
        assertFalse(kept.isClean)
        assertEquals(listOf(Path("docs-v1", "g.txt"), Path("docs-v1")), cleared.removed)
        assertEquals(mapOf("docs" to "dir", "docs/f.txt" to "f"), dst.tree())
        assertTrue(root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess().isClean)
    }

    @Test
    fun `under Anywhere after AsLinks a link to a file is replaced by its copy and a link to a folder stays`() {
        (root / "f").writeRaw("f")
        createDirectoriesRaw(root / "sub")
        (root / "sub" / "s.txt").writeRaw("s")
        links.make(root / "link-to-dir", Path("sub"))
        links.make(root / "link-to-file", Path("f"))
        root.mirrorTo(out()).assertSuccess()

        val byDefault = recording { it.defaultApproved() }
        val report = root.mirrorTo(out(), symlinks = Symlinks.Anywhere, approveRemovals = byDefault).assertSuccess()

        val alreadyEntered = RemovalReason.AtPassedOver(
            skip = WalkSkip.AlreadyEntered(firstRelative = Path("sub")),
            place = Path("link-to-dir"),
        )
        val planned = listOf(
            Removal(Path("link-to-file"), RemovalReason.InTheWay),
            Removal(Path("link-to-dir"), alreadyEntered),
        )
        assertEquals(planned, plans.single().removals)
        assertEquals(listOf(Path("link-to-file")), report.removed)
        assertEquals(listOf(Path("link-to-dir")), report.removalsDeclined.map { it.path })
        assertEquals("f", (dst / "link-to-file").readRaw())
        assertEquals(Ok(Path("sub")), (dst / "link-to-dir").readSymlink())
        assertFalse(report.isClean)
        out().delete("link-to-dir").assertSuccess()
        assertTrue(root.mirrorTo(out(), symlinks = Symlinks.Anywhere).assertSuccess().isClean)
    }

    @Test
    fun `defaultApproved answers every removal but those at a passed over entry`() {
        val stale = Removal(Path("gone.txt"), RemovalReason.Stale)
        val inTheWay = Removal(Path("x"), RemovalReason.InTheWay)
        val atPipe = Removal(Path("pipe"), RemovalReason.AtPassedOver(WalkSkip.Link, place = Path("pipe")))
        val plan = RemovalPlan(
            listOf(stale, atPipe, inTheWay),
            files = 3,
            folders = 0,
            dstEntries = 3,
            sourceEmpty = false,
        )

        assertEquals(listOf(stale, inTheWay), plan.defaultApproved())
        assertEquals(emptyList(), plan.copy(sourceEmpty = true).defaultApproved())
    }
}
