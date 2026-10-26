package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class MirrorTest {
    private val dir = newTempDir()
    private val links = TestLinks()
    private val root = dir / "root"
    private val dst = dir / "dst"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @IgnorableReturnValue
    private fun Path.makeFolders(): Path = also { createDirectoriesRaw(it) }

    /** The writer on [dst] each run mirrors into, which makes [dst] when it is missing. */
    private fun out(): Writer = dst.writer().assertSuccess()

    /** A site builder's output name: `a.md` is written as `a.html`. */
    private fun html(relative: Path): String {
        if (!relative.name.endsWith(".md")) return relative.writerName()
        val name = relative.name.removeSuffix(".md") + ".html"
        return (relative.parent?.let { Path(it, name) } ?: Path(name)).writerName()
    }

    /** A file or link mirrored under its own name, at the path [names] make below both folders. */
    private fun mirrored(vararg names: String): Mirrored {
        val path = Path(names.first(), *names.drop(1).toTypedArray())
        return Mirrored(path, path)
    }

    @Test
    fun `an outputOf rename lists the source path and its output in copied and then in unchanged`() {
        root.makeFolders()
        (root / "a.md").writeRaw("a")

        val first = root.mirrorTo(out(), outputOf = ::html).assertSuccess()
        val second = root.mirrorTo(out(), outputOf = ::html).assertSuccess()

        assertEquals(listOf(Mirrored(Path("a.md"), Path("a.html"))), first.copied)
        assertEquals(listOf(Mirrored(Path("a.md"), Path("a.html"))), second.unchanged)
    }

    @Test
    fun `a mirror copies every file and folder into the destination`() {
        (root / "sub").makeFolders()
        (root / "empty").makeFolders()
        (root / "a.txt").writeRaw("a")
        (root / "sub" / "b.txt").writeRaw("b")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(setOf(mirrored("a.txt"), mirrored("sub", "b.txt")), report.copied.toSet())
        assertEquals("a", (dst / "a.txt").readRaw())
        assertEquals("b", (dst / "sub" / "b.txt").readRaw())
        assertEquals(Ok(FileType.Directory), (dst / "empty").fileType())
    }

    @Test
    fun `a source file made unreadable with its size and time unchanged is current and keeps its old output`() {
        val asset = (root.makeFolders() / "logo.png").writeRaw("png")
        root.mirrorTo(out()).assertSuccess()

        // Skipped where a file at mode 000 can still be read, as for root, which leaves nothing to observe.
        asset.withMode(0, stillWorks = { asset.readText() is Result.Success }) {
            val report = root.mirrorTo(out()).assertSuccess()

            assertEquals(listOf(mirrored("logo.png")), report.unchanged)
            assertEquals(emptyList(), report.failures)
            assertEquals("png", (dst / "logo.png").readRaw())
        }
    }

    @Test
    fun `a mirror copies no file named like a leftover and copies the rest`() {
        val name = ".x.$A_WRITE_ID.tmp"
        root.makeFolders()
        (root / "x").writeRaw("x")
        (root / name).writeRaw("temp")

        val report = root.mirrorTo(out()).assertSuccess()

        val problem = RelativePathProblem.BadName(0, name, NameProblem.LeftoverName)
        val refused = FileError.InvalidRelativePath(dst, name, problem)
        assertEquals(listOf(WalkFailure(root / name, Path(name), refused)), report.failures)
        assertEquals(mapOf("x" to "x"), dst.tree())
    }

    @Test
    fun `a source folder named like a leftover and each entry below it are failures and none is made`() {
        val name = ".pkg.$A_WRITE_ID.dir"
        (root / name / "sub").makeFolders()
        (root / name / "a.txt").writeRaw("a")
        (root / "x").writeRaw("x")

        val report = root.mirrorTo(out()).assertSuccess()

        val problem = RelativePathProblem.BadName(0, name, NameProblem.LeftoverName)
        val failed = listOf(name, "$name/a.txt", "$name/sub").map { entry ->
            WalkFailure(root / entry, Path(entry), FileError.InvalidRelativePath(dst, entry, problem))
        }
        assertEquals(failed, report.failures)
        assertEquals(mapOf("x" to "x"), dst.tree())
    }

    @Test
    fun `a temp a killed copy left in the destination is removed and listed in removed`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        val temp = ".a.txt.$A_WRITE_ID.tmp"
        (dst.makeFolders() / temp).writeRaw("half")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(Path(temp)), report.removed)
        assertEquals(mapOf("a.txt" to "a"), dst.tree())
    }

    @Test
    fun `a stale backup beside a file the run copies is removed first so one run copies the file`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("new")
        (dst.makeFolders() / "a.txt").writeRaw("old")
        val backup = ".a.txt.$A_WRITE_ID.bak"
        (dst / backup).writeRaw("older")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(mirrored("a.txt")), report.copied)
        assertEquals(listOf(Path(backup)), report.removed)
        assertTrue(report.isClean, report.failures.toString())
        assertEquals(mapOf("a.txt" to "new"), dst.tree())
    }

    @Test
    fun `stale leftovers go before the copies and other stale entries after them`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("new")
        (dst.makeFolders() / "a.txt").writeRaw("old")
        (dst / "gone.txt").writeRaw("gone")
        val temp = ".a.txt.$A_WRITE_ID.tmp"
        (dst / temp).writeRaw("half")
        val plans = mutableListOf<List<Path>>()

        val report = root.mirrorTo(
            out(),
            approveRemovals = { plan ->
                plans += plan.removals.map { it.path }
                plan.removals
            },
        ).assertSuccess()

        assertEquals(listOf(listOf(Path(temp), Path("gone.txt"))), plans)
        assertEquals(listOf(Path(temp), Path("gone.txt")), report.removed)
        assertEquals(mapOf("a.txt" to "new"), dst.tree())
    }

    @Test
    fun `a mirror copies onto a link in the destination without writing through it`() {
        (root / "sub").makeFolders()
        (root / "sub" / "a.txt").writeRaw("new")
        (dst / "sub").makeFolders()
        val theirs = (dir / "theirs.txt").writeRaw("theirs")
        links.make(dst / "sub" / "a.txt", theirs)

        val asked = mutableListOf<List<Path>>()

        val report = root.mirrorTo(
            out(),
            approveRemovals = { plan ->
                asked += plan.removals.map { it.path }
                plan.removals
            },
        ).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals(listOf(listOf(Path("sub", "a.txt"))), asked)
        assertEquals(listOf(Path("sub", "a.txt")), report.removed)
        assertEquals(listOf(mirrored("sub", "a.txt")), report.copied)
        assertEquals("theirs", theirs.readRaw())
        assertEquals(Ok(FileType.Regular), (dst / "sub" / "a.txt").fileType(followLinks = false))
        assertEquals("new", (dst / "sub" / "a.txt").readRaw())
    }

    @Test
    fun `a mirror whose own copy writes through the writer replaces a link at a file's output`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("new")
        dst.makeFolders()
        val theirs = (dir / "theirs.txt").writeRaw("theirs")
        links.make(dst / "a.txt", theirs)

        val report = root.mirrorTo(
            out(),
            copy = { source, out, name -> out.writeText(name, source.path.readRaw().uppercase()).map { } },
        ).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals(listOf(mirrored("a.txt")), report.copied)
        assertEquals("theirs", theirs.readRaw())
        assertEquals("NEW", (dst / "a.txt").readRaw())
    }

    @Test
    fun `a mirror whose approveRemovals answers no removal keeps a link at a file's output and fails that file`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("new")
        dst.makeFolders()
        val theirs = (dir / "theirs.txt").writeRaw("theirs")
        links.make(dst / "a.txt", theirs)

        val report = root.mirrorTo(out(), approveRemovals = { emptyList() }).assertSuccess()

        val refused = FileError.NotRegularFile(dst / "a.txt", FileType.Symlink)
        assertEquals(listOf(WalkFailure(root / "a.txt", Path("a.txt"), refused)), report.failures)
        assertEquals(listOf(Path("a.txt")), report.removalsDeclined.map { it.path })
        assertEquals(Ok(theirs), (dst / "a.txt").readSymlink())
        assertEquals("theirs", theirs.readRaw())
    }

    @Test
    fun `a mirror whose approveRemovals keeps a link at a folder's output fails each entry below it with that link`() {
        (root / "docs").makeFolders()
        (root / "docs" / "a.md").writeRaw("new")
        (dst / "other").makeFolders()
        links.make(dst / "docs", Path("other"))

        val report = root.mirrorTo(out(), approveRemovals = { emptyList() }).assertSuccess()

        val refused = FileError.NotADirectory(dst / "docs", FileType.Symlink)
        val failed = listOf("docs", "docs/a.md").map { WalkFailure(root / it, Path(it), refused) }
        assertEquals(failed, report.failures)
        assertEquals(emptyList(), report.copied)
        assertEquals(emptyList(), SystemFileSystem.list(dst / "other"))
        assertFalse(report.isClean)
    }

    @Test
    fun `a mirror puts a real folder in place of a folder link planted in the destination`() {
        (root / "sub").makeFolders()
        (root / "sub" / "a.png").writeRaw("a")
        val outside = (dir / "outside").makeFolders()
        dst.makeFolders()
        links.make(dst / "sub", outside)

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals(emptyList(), SystemFileSystem.list(outside))
        assertEquals(Ok(FileType.Directory), (dst / "sub").fileType(followLinks = false))
        assertEquals("a", (dst / "sub" / "a.png").readRaw())
    }

    @Test
    fun `a mirror makes empty folders and removes what the source no longer has in reverse walk order`() {
        (root / "empty").makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "old" / "deeper").makeFolders()
        (dst / "old" / "deeper" / "c.txt").writeRaw("c")
        (dst / "stale.txt").writeRaw("s")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(setOf(dst / "a.txt", dst / "empty"), SystemFileSystem.list(dst).toSet())
        val removed = listOf(Path("old", "deeper", "c.txt"), Path("old", "deeper"), Path("old"), Path("stale.txt"))
        assertEquals(removed, report.removed)
        assertEquals(emptyList(), report.notRemoved)
    }

    @Test
    fun `a mirror replaces a destination file that is now a source folder and the reverse`() {
        (root / "was-file").makeFolders()
        (root / "was-file" / "a.txt").writeRaw("a")
        (root / "was-folder").writeRaw("b")
        (dst / "was-folder" / "deeper").makeFolders()
        (dst / "was-folder" / "deeper" / "c.txt").writeRaw("c")
        (dst / "was-file").writeRaw("old")

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals("a", (dst / "was-file" / "a.txt").readRaw())
        assertEquals("b", (dst / "was-folder").readRaw())
    }

    @Test
    fun `a mirror keeps what a folder enter refused holds in the destination and reports it held`() {
        (root / ".git").makeFolders()
        (root / ".git" / "config").writeRaw("theirs")
        (root / "a.txt").writeRaw("a")
        (dst / ".git" / "hooks").makeFolders()
        (dst / ".git" / "config").writeRaw("kept")
        (dst / ".git" / "hooks" / "pre-commit").writeRaw("hook")

        val report = root.mirrorTo(out(), enter = { it.path.name != ".git" }).assertSuccess()

        assertEquals(listOf(WalkEntry.Skipped(root / ".git", Path(".git"), WalkSkip.Refused)), report.held)
        assertEquals("kept", (dst / ".git" / "config").readRaw())
        assertEquals("hook", (dst / ".git" / "hooks" / "pre-commit").readRaw())
        assertEquals(emptyList(), report.removed)
    }

    @Test
    fun `a mirror never removes what keep answers true for and does not look inside a kept folder`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / ".git").makeFolders()
        (dst / ".git" / "HEAD").writeRaw("ref")
        (dst / "CNAME").writeRaw("example.org")
        (dst / "stale.txt").writeRaw("s")
        val asked = mutableListOf<Path>()

        val report = root.mirrorTo(
            out(),
            keep = { relative ->
                asked += relative
                relative == Path(".git") || relative == Path("CNAME")
            },
        ).assertSuccess()

        assertEquals("ref", (dst / ".git" / "HEAD").readRaw())
        assertEquals("example.org", (dst / "CNAME").readRaw())
        assertEquals(setOf(dst / ".git", dst / "CNAME", dst / "a.txt"), SystemFileSystem.list(dst).toSet())
        assertEquals(setOf(Path(".git"), Path("CNAME"), Path("stale.txt")), asked.toSet())
        assertEquals(listOf(mirrored("a.txt")), report.copied)
        assertEquals(listOf(Path("stale.txt")), report.removed)
        assertEquals(emptyList(), report.held)
        assertEquals(emptyList(), report.notRemoved)
    }

    @Test
    fun `a keep that answers true for a file keeps the folders it lies in though the source has none of them`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "vendor").makeFolders()
        (dst / "vendor" / "lib.js").writeRaw("lib")
        val plans = mutableListOf<RemovalPlan>()

        val report = root.mirrorTo(
            out(),
            keep = { it == Path("vendor/lib.js") },
            approveRemovals = { plan ->
                plans += plan
                plan.defaultApproved()
            },
        ).assertSuccess()

        assertEquals(emptyList(), plans)
        assertEquals("lib", (dst / "vendor" / "lib.js").readRaw())
        assertEquals(emptyList(), report.notRemoved)
        assertTrue(report.isClean)
    }

    @Test
    fun `a folder keeps its name and the files in it are renamed inside it`() {
        val thing = (root / "thing.md").makeFolders()
        (thing / "x.md").writeRaw("x")
        (thing / "y.txt").writeRaw("y")
        (root / "a.md").writeRaw("a")
        val asked = mutableListOf<Path>()

        val report = root.mirrorTo(
            out(),
            outputOf = { relative -> relative.writerName().replace(".md", ".html").also { asked += relative } },
        ).assertSuccess()

        val expected = mapOf("a.html" to "a", "thing.md" to "dir", "thing.md/x.html" to "x", "thing.md/y.txt" to "y")
        assertEquals(expected, dst.tree())
        assertEquals(setOf(Path("a.md"), Path("thing.md", "x.md"), Path("thing.md", "y.txt")), asked.toSet())
        val copied = setOf(
            Mirrored(Path("a.md"), Path("a.html")),
            Mirrored(Path("thing.md", "x.md"), Path("thing.md", "x.html")),
            mirrored("thing.md", "y.txt"),
        )
        assertEquals(copied, report.copied.toSet())
        assertEquals(true, report.isClean)
    }

    @Test
    fun `under Inside a held file keeps what the destination has under its name and its output's`() {
        root.makeFolders()
        (root / "a.md").writeRaw("a")
        (root / "b.txt").writeRaw("b")
        root.mirrorTo(out(), symlinks = Symlinks.Inside, outputOf = ::html).assertSuccess()
        (dst / "a.md").writeRaw("old")
        SystemFileSystem.delete(root / "a.md")
        links.make(root / "a.md", dir / "gone")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside, outputOf = ::html).assertSuccess()

        assertEquals(listOf(WalkSkip.DanglingLink), report.held.map { it.why })
        assertEquals(emptyList(), report.removed)
        assertEquals(mapOf("a.html" to "a", "a.md" to "old", "b.txt" to "b"), dst.tree())
    }

    @Test
    fun `under Inside a mirror removes what the destination holds at a folder link leading outside once approved`() {
        val elsewhere = (dir / "elsewhere").makeFolders()
        (elsewhere / "x.txt").writeRaw("x")
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "linked", elsewhere)
        (dst / "linked").makeFolders()
        (dst / "linked" / "y.txt").writeRaw("y")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside, approveRemovals = { it.removals }).assertSuccess()

        assertEquals(emptyList(), report.held)
        assertEquals(listOf(Path("linked", "y.txt"), Path("linked")), report.removed)
        assertEquals(listOf(dst / "a.txt"), SystemFileSystem.list(dst))
    }

    @Test
    fun `under Inside a mirror removes the copy made through a link now leading outside once approved`() {
        val outside = (dir / "outside").makeFolders()
        (outside / "secret.txt").writeRaw("s")
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "leak.txt", outside / "secret.txt")
        root.mirrorTo(out(), symlinks = Symlinks.Anywhere).assertSuccess()
        assertEquals("s", (dst / "leak.txt").readRaw())

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside, approveRemovals = { it.removals }).assertSuccess()

        assertEquals(emptyList(), report.held)
        assertEquals(listOf(Path("leak.txt") to WalkSkip.LeadsOutside), report.passedOver.map { it.relative to it.why })
        assertEquals(listOf(Path("leak.txt")), report.removed)
        assertEquals(listOf(dst / "a.txt"), SystemFileSystem.list(dst))
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Inside a mirror lists a link leading outside as passed over and is clean with nothing at its place`() {
        val outside = (dir / "outside").makeFolders()
        (outside / "secret.txt").writeRaw("s")
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "leak.txt", outside / "secret.txt")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        assertEquals(listOf(Path("leak.txt") to WalkSkip.LeadsOutside), report.passedOver.map { it.relative to it.why })
        assertEquals(emptyList(), report.held)
        assertEquals(emptyList(), report.removed)
        assertEquals(true, report.isClean)
    }

    @Test
    fun `a mirror that declined removing what was at the place of a passed over link lists it and is not clean`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "b.txt", Path("a.txt"))
        root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        val report = root.mirrorTo(
            out(),
            symlinks = Symlinks.NoLinks,
            approveRemovals = { emptyList() },
        ).assertSuccess()

        assertEquals(listOf(Path("b.txt")), report.passedOver.map { it.relative })
        assertEquals(listOf(Path("b.txt")), report.removalsDeclined.map { it.path })
        assertFalse(report.isClean)
    }

    @Test
    fun `under NoLinks the renamed output of a link passed over is removed at the link's place once approved`() {
        root.makeFolders()
        (root / "a.md").writeRaw("a")
        links.make(root / "b.md", Path("a.md"))
        root.mirrorTo(out(), symlinks = Symlinks.Inside, outputOf = ::html).assertSuccess()
        assertEquals("a", (dst / "b.html").readRaw())
        val planned = mutableListOf<Removal>()

        val report = root
            .mirrorTo(
                out(),
                symlinks = Symlinks.NoLinks,
                outputOf = ::html,
                approveRemovals = { plan -> plan.removals.also { planned += it } },
            ).assertSuccess()

        assertEquals(listOf(Path("b.md") to WalkSkip.Link), report.passedOver.map { it.relative to it.why })
        val why = RemovalReason.AtPassedOver(WalkSkip.Link, place = Path("b.md"))
        assertEquals(listOf(Removal(Path("b.html"), why)), planned)
        assertEquals(listOf(Path("b.html")), report.removed)
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `a mirror is clean when a stale entry beside a link it passed over was removed`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "b.txt", Path("a.txt"))
        dst.makeFolders()
        (dst / "b.txt.old").writeRaw("old")

        val report = root.mirrorTo(out(), symlinks = Symlinks.NoLinks).assertSuccess()

        assertEquals(listOf(Path("b.txt")), report.passedOver.map { it.relative })
        assertEquals(listOf(Path("b.txt.old")), report.removed)
        assertEquals(true, report.isClean)
    }

    @Test
    fun `a mirror under NoLinks removes what the destination has at a link's place once approved`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "b.txt", Path("a.txt"))
        root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()
        assertEquals("a", (dst / "b.txt").readRaw())

        val report = root.mirrorTo(
            out(),
            symlinks = Symlinks.NoLinks,
            approveRemovals = { it.removals },
        ).assertSuccess()

        assertEquals(emptyList(), report.held)
        assertEquals(listOf(Path("b.txt") to WalkSkip.Link), report.passedOver.map { it.relative to it.why })
        assertEquals(listOf(Path("b.txt")), report.removed)
        assertEquals(listOf(dst / "a.txt"), SystemFileSystem.list(dst))
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `a mirror under AsLinks makes each link in the destination with its target as written`() {
        (root / "sub").makeFolders()
        (root / "sub" / "b.txt").writeRaw("b")
        links.make(root / "current", Path("sub/b.txt"))
        links.make(root / "sub" / "up", Path("../current"))
        links.make(root / "nowhere", Path("missing"))

        val report = root.mirrorTo(out(), symlinks = Symlinks.AsLinks).assertSuccess()

        val made = mapOf(
            "current" to "-> sub/b.txt",
            "nowhere" to "-> missing",
            "sub" to "dir",
            "sub/b.txt" to "b",
            "sub/up" to "-> ../current",
        )
        assertEquals(made, dst.tree())
        val copied = setOf(mirrored("current"), mirrored("nowhere"), mirrored("sub", "b.txt"), mirrored("sub", "up"))
        assertEquals(copied, report.copied.toSet())
        assertTrue(report.isClean)
    }

    @Test
    fun `a mirror under AsLinks leaves a link with the same target alone and lists it unchanged`() {
        root.makeFolders()
        links.make(root / "current", Path("a.txt"))
        (root / "a.txt").writeRaw("a")
        root.mirrorTo(out(), symlinks = Symlinks.AsLinks).assertSuccess()

        val report = root.mirrorTo(out(), symlinks = Symlinks.AsLinks).assertSuccess()

        assertEquals(setOf(mirrored("a.txt"), mirrored("current")), report.unchanged.toSet())
        assertEquals(emptyList(), report.copied)
        assertTrue(report.isClean)
    }

    @Test
    fun `a mirror under AsLinks switches a link with another target and lists it copied`() {
        root.makeFolders()
        links.make(root / "current", Path("a.txt"))
        dst.makeFolders()
        links.make(dst / "current", Path("old.txt"))

        val report = root.mirrorTo(out(), symlinks = Symlinks.AsLinks).assertSuccess()

        assertEquals(mapOf("current" to "-> a.txt"), dst.tree())
        assertEquals(listOf(mirrored("current")), report.copied)
        assertEquals(emptyList(), report.removed)
    }

    @Test
    fun `under AsLinks a link leading out of the source is not made and is passed over and the run not clean`() {
        val outside = (dir / "outside").makeFolders()
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "up", Path("../outside"))
        links.make(root / "absolute", outside)
        links.make(root / "back", Path("../root/a.txt"))
        dst.makeFolders()
        (dst / "up").writeRaw("stale")

        val report = root.mirrorTo(
            out(),
            symlinks = Symlinks.AsLinks,
            approveRemovals = { it.removals },
        ).assertSuccess()

        val passed = listOf(
            WalkEntry.Skipped(root / "absolute", Path("absolute"), WalkSkip.LeadsOutside),
            WalkEntry.Skipped(root / "back", Path("back"), WalkSkip.LeadsOutside),
            WalkEntry.Skipped(root / "up", Path("up"), WalkSkip.LeadsOutside),
        )
        assertEquals(passed, report.passedOver)
        assertEquals(mapOf("a.txt" to "a"), dst.tree())
        assertEquals(listOf(Path("up")), report.removed)
        assertFalse(report.isClean)
    }

    @Test
    fun `a mirror under AsLinks removes a file or folder standing where a link goes and makes the link`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "file", Path("a.txt"))
        links.make(root / "folder", Path("a.txt"))
        (dst / "folder").makeFolders()
        (dst / "folder" / "x.txt").writeRaw("x")
        (dst / "file").writeRaw("old")

        val report = root.mirrorTo(out(), symlinks = Symlinks.AsLinks).assertSuccess()

        assertEquals(mapOf("a.txt" to "a", "file" to "-> a.txt", "folder" to "-> a.txt"), dst.tree())
        assertEquals(listOf(Path("file"), Path("folder", "x.txt"), Path("folder")), report.removed)
        assertTrue(report.isClean)
    }

    @Test
    fun `a mirror under AsLinks whose approveRemovals answers no removal fails a link whose place is taken`() {
        root.makeFolders()
        links.make(root / "file", Path("a.txt"))
        dst.makeFolders()
        (dst / "file").writeRaw("old")

        val report = root.mirrorTo(
            out(),
            symlinks = Symlinks.AsLinks,
            approveRemovals = { emptyList() },
        ).assertSuccess()

        val refused = FileError.AlreadyExists(dst / "file", FileType.Regular)
        assertEquals(listOf(WalkFailure(root / "file", Path("file"), refused)), report.failures)
        assertEquals(mapOf("file" to "old"), dst.tree())
    }

    @Test
    fun `a mirror keeps what a source folder it could not read holds in the destination`() {
        val locked = (root / "locked").makeFolders()
        (locked / "a.txt").writeRaw("new")
        (dst / "locked" / "deeper").makeFolders()
        (dst / "locked" / "a.txt").writeRaw("old")
        (dst / "locked" / "deeper" / "b.txt").writeRaw("b")

        locked.whileUnlistable {
            val report = root.mirrorTo(out()).assertSuccess()

            assertEquals(listOf(locked), report.failures.map { it.path })
            assertEquals("old", (dst / "locked" / "a.txt").readRaw())
            assertEquals("b", (dst / "locked" / "deeper" / "b.txt").readRaw())
        }
    }

    @Test
    fun `a mirror reports a stale destination folder it could not read once and leaves it`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        val old = (dst / "old").makeFolders()
        (old / "x.txt").writeRaw("x")

        old.whileUnlistable {
            val report = root.mirrorTo(out()).assertSuccess()

            assertEquals(listOf(old), report.notRemoved.map { it.path })
            assertEquals(emptyList(), report.removed)
        }
        assertEquals("x", (old / "x.txt").readRaw())
    }

    @Test
    fun `a mirror leaves a stale folder holding one it could not read out of the planned removals`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        val inner = (dst / "old" / "inner").makeFolders()
        (inner / "x.txt").writeRaw("x")
        var asked = false

        inner.whileUnlistable {
            val report = root.mirrorTo(
                out(),
                approveRemovals = {
                    asked = true
                    it.removals
                },
            ).assertSuccess()

            assertEquals(listOf(inner), report.notRemoved.map { it.path })
            assertEquals(emptyList(), report.removed)
            assertFalse(asked)
        }
        assertEquals("x", (inner / "x.txt").readRaw())
    }

    @Test
    fun `a FIFO at a file's output is removed and the file copied in its place`() {
        root.makeFolders()
        (root / "a").writeRaw("a")
        val fifo = dst.makeFolders() / "a"
        // Windows has no FIFOs, which leaves nothing to observe.
        if (fifo.mkfifo() !is Result.Success) return

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals(listOf(mirrored("a")), report.copied)
        assertEquals(Ok(FileType.Regular), fifo.fileType(followLinks = false))
        assertEquals("a", fifo.readRaw())
    }

    @Test
    fun `a mirror asks approveRemovals once with every planned removal in removal order and removes them`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "old").makeFolders()
        (dst / "old" / "x.txt").writeRaw("x")
        (dst / "stale.txt").writeRaw("s")
        val asked = mutableListOf<List<Path>>()

        val report = root.mirrorTo(
            out(),
            approveRemovals = { planned ->
                asked += planned.removals.map { it.path }
                planned.removals
            },
        ).assertSuccess()

        val planned = listOf(Path("old", "x.txt"), Path("old"), Path("stale.txt"))
        assertEquals(listOf(planned), asked)
        assertEquals(planned, report.removed)
        assertEquals(emptyList(), report.removalsDeclined)
        assertEquals(listOf(dst / "a.txt"), SystemFileSystem.list(dst))
    }

    @Test
    fun `a mirror whose approveRemovals answers no removal removes nothing and lists each planned one as declined`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "old").makeFolders()
        (dst / "old" / "x.txt").writeRaw("x")
        (dst / "stale.txt").writeRaw("s")

        val report = root.mirrorTo(out(), approveRemovals = { emptyList() }).assertSuccess()

        val declined = listOf(Path("old", "x.txt"), Path("old"), Path("stale.txt"))
        assertEquals(declined, report.removalsDeclined.map { it.path })
        assertEquals(emptyList(), report.removed)
        assertEquals(listOf(mirrored("a.txt")), report.copied)
        assertEquals("x", (dst / "old" / "x.txt").readRaw())
        assertEquals("s", (dst / "stale.txt").readRaw())
    }

    @Test
    fun `a mirror asks approveRemovals about a folder where the source now has a file before stale entries`() {
        root.makeFolders()
        (root / "x").writeRaw("x")
        (dst / "x").makeFolders()
        (dst / "x" / "a.txt").writeRaw("a")
        (dst / "stale.txt").writeRaw("s")
        val asked = mutableListOf<List<Path>>()

        val report = root.mirrorTo(
            out(),
            approveRemovals = { planned ->
                asked += planned.removals.map { it.path }
                planned.removals
            },
        ).assertSuccess()

        val planned = listOf(Path("x", "a.txt"), Path("x"), Path("stale.txt"))
        assertEquals(listOf(planned), asked)
        assertEquals(planned, report.removed)
        assertEquals(emptyList(), report.failures)
        assertEquals("x", (dst / "x").readRaw())
    }

    @Test
    fun `a mirror whose approveRemovals answers no removal keeps a dst folder where the source now has a file`() {
        root.makeFolders()
        (root / "x").writeRaw("x")
        (root / "b.txt").writeRaw("b")
        (dst / "x").makeFolders()
        (dst / "x" / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out(), approveRemovals = { emptyList() }).assertSuccess()

        val failure = WalkFailure(root / "x", Path("x"), FileError.NotRegularFile(dst / "x", FileType.Directory))
        assertEquals(listOf(failure), report.failures)
        assertEquals(listOf(Path("x", "a.txt"), Path("x")), report.removalsDeclined.map { it.path })
        assertEquals(listOf(mirrored("b.txt")), report.copied)
        assertEquals(emptyList(), report.removed)
        assertEquals("a", (dst / "x" / "a.txt").readRaw())
    }

    @Test
    fun `a mirror with nothing to remove does not ask approveRemovals`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        var asked = false

        val report = root.mirrorTo(
            out(),
            approveRemovals = {
                asked = true
                emptyList()
            },
        ).assertSuccess()

        assertFalse(asked)
        assertEquals(emptyList(), report.removalsDeclined)
    }

    @Test
    fun `a mirror of a root holding only an empty folder removes nothing when more than a share would go`() {
        (root / "lost+found").makeFolders()
        dst.makeFolders()
        repeat(60) { (dst / "photo$it.jpg").writeRaw("p") }

        val report = root.mirrorTo(
            out(),
            approveRemovals = { if (it.removals.size <= 50) it.removals else emptyList() },
        ).assertSuccess()

        assertEquals(60, report.removalsDeclined.size)
        assertEquals(emptyList(), report.removed)
        assertEquals(61, SystemFileSystem.list(dst).size)
    }

    @Test
    fun `two entries with one output are copied once and the later one is an OutputClash on every run`() {
        root.makeFolders()
        (root / "x.html").writeRaw("html")
        (root / "x.md").writeRaw("md")
        val clash = WalkFailure(root / "x.md", Path("x.md"), FileError.OutputClash(dst / "x.html", Path("x.html")))

        val first = root.mirrorTo(out(), outputOf = ::html).assertSuccess()
        val second = root.mirrorTo(out(), outputOf = ::html).assertSuccess()

        assertEquals(listOf(clash), first.failures)
        assertEquals(listOf(mirrored("x.html")), first.copied)
        assertEquals(listOf(clash), second.failures)
        assertEquals(emptyList(), second.copied)
        assertEquals(listOf(mirrored("x.html")), second.unchanged)
        assertEquals("html", (dst / "x.html").readRaw())
    }

    @Test
    fun `a folder whose output a file took first is an OutputClash and is not made`() {
        (root / "x.html").makeFolders()
        (root / "x.md").writeRaw("md")

        val report = root.mirrorTo(out(), outputOf = ::html).assertSuccess()

        val clash = WalkFailure(root / "x.html", Path("x.html"), FileError.OutputClash(dst / "x.html", Path("x.md")))
        assertEquals(listOf(clash), report.failures)
        assertEquals("md", (dst / "x.html").readRaw())
    }

    @Test
    fun `a folder that loses its output to a file is the only failure and nothing below it is copied`() {
        (root / "x.html").makeFolders()
        (root / "x.html" / "c.txt").writeRaw("c")
        (root / "x.md").writeRaw("md")

        val report = root.mirrorTo(out(), outputOf = ::html).assertSuccess()

        val clash = WalkFailure(root / "x.html", Path("x.html"), FileError.OutputClash(dst / "x.html", Path("x.md")))
        assertEquals(listOf(clash), report.failures)
        assertEquals(listOf(Mirrored(Path("x.md"), Path("x.html"))), report.copied)
        assertEquals("md", (dst / "x.html").readRaw())
    }

    @Test
    fun `under Inside a mirror keeps the renamed output of a source it could not read or passed over`() {
        val pages = (root / "pages").makeFolders()
        (pages / "a.md").writeRaw("a")
        (root / "b.md").writeRaw("b")
        root.mirrorTo(out(), symlinks = Symlinks.Inside, outputOf = ::html).assertSuccess()
        SystemFileSystem.delete(root / "b.md")
        links.make(root / "b.md", dir / "gone")

        pages.withMode(0b100_000_000, stillWorks = { (pages / "a.md").info() is Result.Success }) {
            val report = root.mirrorTo(out(), symlinks = Symlinks.Inside, outputOf = ::html).assertSuccess()

            assertEquals(listOf(pages / "a.md"), report.failures.map { it.path })
            assertEquals(listOf(WalkSkip.DanglingLink), report.held.map { it.why })
            assertEquals("a", (dst / "pages" / "a.html").readRaw())
            assertEquals("b", (dst / "b.html").readRaw())
        }
    }

    @Test
    fun `a mirror keeps what a source folder it could not read holds under its own name`() {
        val folder = (root / "x.md").makeFolders()
        (folder / "a.md").writeRaw("a")
        (root / "b.md").writeRaw("b")
        root.mirrorTo(out(), outputOf = ::html).assertSuccess()

        folder.whileUnlistable {
            val report = root.mirrorTo(out(), outputOf = ::html).assertSuccess()

            assertEquals(listOf(folder), report.failures.map { it.path })
            assertEquals(mapOf("b.html" to "b", "x.md" to "dir", "x.md/a.html" to "a"), dst.tree())
        }
    }

    @Test
    fun `a mirror removes the copy of an entry gone from the source on the run after it went`() {
        (root / "a").makeFolders()
        (root / "b.txt").writeRaw("b")
        root.mirrorTo(out()).assertSuccess()

        // Asked about a before b.txt is looked up, so b.txt is listed but gone by its lookup.
        val goneWhileWalked = root.mirrorTo(
            out(),
            enter = {
                (root / "b.txt").delete().assertSuccess()
                true
            },
        )

        assertEquals(listOf(WalkSkip.Gone), goneWhileWalked.assertSuccess().held.map { it.why })
        assertEquals("b", (dst / "b.txt").readRaw())

        val next = root.mirrorTo(out()).assertSuccess()

        assertEquals(listOf(Path("b.txt")), next.removed)
        assertEquals(listOf(dst / "a"), SystemFileSystem.list(dst))
    }

    @Test
    fun `a mirror asks isCurrent only about a regular file at the target and leaves a current one alone`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (root / "linked.txt").writeRaw("l")
        root.mirrorTo(out()).assertSuccess()
        SystemFileSystem.delete(dst / "linked.txt")
        links.make(dst / "linked.txt", (dir / "theirs.txt").writeRaw("l"))
        (root / "new.txt").writeRaw("n")
        val asked = mutableListOf<Path>()

        val report = root.mirrorTo(
            out(),
            isCurrent = { source, existing ->
                asked += source.relative
                existing.size == source.size && existing.modifiedAt == source.modifiedAt
            },
        ).assertSuccess()

        assertEquals(listOf(Path("a.txt")), asked)
        assertEquals(listOf(mirrored("a.txt")), report.unchanged)
        assertEquals(setOf(mirrored("linked.txt"), mirrored("new.txt")), report.copied.toSet())
        assertEquals(Ok(FileType.Regular), (dst / "linked.txt").fileType(followLinks = false))
    }

    @Test
    fun `a mirror reports a copy that failed and goes on with the rest`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (root / "b.txt").writeRaw("b")
        dst.makeFolders()
        (dst / "a.txt").writeRaw("old")
        val refused = FileError.WriteFailed(dst / "a.txt", IoCause.Denied, null)

        val report = root.mirrorTo(
            out(),
            copy = { source, out, name ->
                if (source.relative == Path("a.txt")) Err(refused) else out.copyFrom(source.path, name).map { }
            },
        ).assertSuccess()

        assertEquals(listOf(WalkFailure(root / "a.txt", Path("a.txt"), refused)), report.failures)
        assertEquals(listOf(mirrored("b.txt")), report.copied)
        assertEquals(setOf(dst / "a.txt", dst / "b.txt"), SystemFileSystem.list(dst).toSet())
        assertEquals("old", (dst / "a.txt").readRaw())
    }

    @Test
    fun `a mirror refuses a source inside the destination and changes nothing there`() {
        (dst / "inner").makeFolders()
        (dst / "inner" / "a.txt").writeRaw("a")
        (dst / "b.txt").writeRaw("b")
        val inner = dst / "inner"

        assertEquals(Err(FileError.MirrorOverlap(inner, dst)), inner.mirrorTo(out()))
        assertEquals(Err(FileError.MirrorOverlap(dst, dst)), dst.mirrorTo(out()))

        assertEquals(setOf(dst / "inner", dst / "b.txt"), SystemFileSystem.list(dst).toSet())
        assertEquals(listOf(dst / "inner" / "a.txt"), SystemFileSystem.list(dst / "inner"))
    }

    @Test
    fun `a mirror refuses a source that is a file and leaves the destination alone`() {
        val file = (dir / "a.txt").writeRaw("a")
        dst.makeFolders()
        (dst / "b.txt").writeRaw("b")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.mirrorTo(out()))

        assertEquals(listOf(dst / "b.txt"), SystemFileSystem.list(dst))
    }

    @Test
    fun `a mirror of an empty source removes nothing by default and is not clean`() {
        root.makeFolders()
        (dst / "sub").makeFolders()
        (dst / "sub" / "b.txt").writeRaw("b")
        (dst / "a.txt").writeRaw("a")
        val asked = mutableListOf<RemovalPlan>()

        val report = root.mirrorTo(out()).assertSuccess()
        root.mirrorTo(
            out(),
            approveRemovals = { plan ->
                asked += plan
                emptyList()
            },
        ).assertSuccess()

        val planned = listOf(Path("sub", "b.txt"), Path("sub"), Path("a.txt"))
        assertEquals(planned, report.removalsDeclined.map { it.path })
        assertEquals(emptyList(), report.removed)
        assertFalse(report.isClean)
        val stale = planned.map { Removal(it, RemovalReason.Stale) }
        assertEquals(listOf(RemovalPlan(stale, files = 2, folders = 1, dstEntries = 3, sourceEmpty = true)), asked)
        assertEquals("a", (dst / "a.txt").readRaw())
        assertEquals("b", (dst / "sub" / "b.txt").readRaw())
    }

    @Test
    fun `a mirror of an empty source empties the destination when approveRemovals says yes`() {
        root.makeFolders()
        (dst / "sub").makeFolders()
        (dst / "sub" / "b.txt").writeRaw("b")
        (dst / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out(), approveRemovals = { it.removals }).assertSuccess()

        assertEquals(listOf(Path("sub", "b.txt"), Path("sub"), Path("a.txt")), report.removed)
        assertTrue(report.isClean)
        assertEquals(emptyList(), SystemFileSystem.list(dst))
    }

    @Test
    fun `a mirror of an empty source into an empty destination is clean and asks nothing`() {
        root.makeFolders()
        dst.makeFolders()
        var asked = false

        val report = root.mirrorTo(
            out(),
            approveRemovals = {
                asked = true
                emptyList()
            },
        )

        val nothingDone =
            MirrorReport(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                sourceEmpty = true,
                unclean = emptyList(),
            )
        assertEquals(Ok(nothingDone), report)
        assertFalse(asked)
    }

    @Test
    fun `a mirror leaves out a destination inside the source and does not report it held`() {
        (root / "docs").makeFolders()
        (root / "a.txt").writeRaw("a")
        (root / "docs" / "b.txt").writeRaw("b")
        val inside = root / "out"

        val report = root.mirrorTo(inside.writer().assertSuccess()).assertSuccess()

        assertEquals(emptyList(), report.held)
        assertEquals(setOf(inside / "a.txt", inside / "docs"), SystemFileSystem.list(inside).toSet())
        assertEquals("b", (inside / "docs" / "b.txt").readRaw())
    }

    /** Mirrors [root] into its own `out` under [mode], where `site-link` leads to `out` and `sub-link` into it. */
    private fun mirrorWithLinksToItsDestination(mode: Symlinks): MirrorReport {
        val inside = (root / "out" / "sub").makeFolders().parent!!
        (root / "a.txt").writeRaw("a")
        links.make(root / "site-link", Path("out"))
        links.make(root / "sub-link", Path("out", "sub"))
        return root.mirrorTo(inside.writer().assertSuccess(), symlinks = mode).assertSuccess()
    }

    private val linksToTheDestination = listOf(
        WalkEntry.Skipped(root / "site-link", Path("site-link"), WalkSkip.Excluded),
        WalkEntry.Skipped(root / "sub-link", Path("sub-link"), WalkSkip.Excluded),
    )

    @Test
    fun `under Inside a source link to the destination or into it is held as Excluded and the run clean`() {
        val report = mirrorWithLinksToItsDestination(Symlinks.Inside)

        assertEquals(linksToTheDestination, report.held)
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Anywhere a source link to the destination or into it is held as Excluded and the run clean`() {
        val report = mirrorWithLinksToItsDestination(Symlinks.Anywhere)

        assertEquals(linksToTheDestination, report.held)
        assertTrue(report.isClean, report.toString())
    }

    /** Mirrors [root], which holds a `sub` the copy keeps, into its own `out` under [Symlinks.AsLinks], then [mode]. */
    private fun linksToTheDestinationMadeThenFollowed(mode: Symlinks): MirrorReport {
        val inside = (root / "out").makeFolders()
        (root / "sub").makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "site-link", Path("out"))
        links.make(root / "sub-link", Path("out", "sub"))
        root.mirrorTo(inside.writer().assertSuccess()).assertSuccess()
        return root.mirrorTo(inside.writer().assertSuccess(), symlinks = mode).assertSuccess()
    }

    @Test
    fun `under Inside the copy an AsLinks run made of a link to the destination stays and the run is clean`() {
        val report = linksToTheDestinationMadeThenFollowed(Symlinks.Inside)

        assertEquals(linksToTheDestination, report.held)
        assertEquals(Ok(Path("out")), (root / "out" / "site-link").readSymlink())
        assertEquals(Ok(Path("out", "sub")), (root / "out" / "sub-link").readSymlink())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Anywhere the copy an AsLinks run made of a link to the destination stays and the run is clean`() {
        val report = linksToTheDestinationMadeThenFollowed(Symlinks.Anywhere)

        assertEquals(linksToTheDestination, report.held)
        assertEquals(Ok(Path("out")), (root / "out" / "site-link").readSymlink())
        assertEquals(Ok(Path("out", "sub")), (root / "out" / "sub-link").readSymlink())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Inside a source link to a destination outside the source leads outside and is not held`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "site-link", dst)

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        val passed = listOf(WalkEntry.Skipped(root / "site-link", Path("site-link"), WalkSkip.LeadsOutside))
        assertEquals(passed, report.passedOver)
        assertEquals(emptyList(), report.held)
        assertEquals(listOf(mirrored("a.txt")), report.copied)
    }

    @Test
    fun `under AsLinks a link to a destination inside the source is made by a relative target and not an absolute`() {
        val inside = (root / "out").makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "absolute", inside)
        links.make(root / "relative", Path("out"))

        val report = root.mirrorTo(inside.writer().assertSuccess()).assertSuccess()

        val passed = listOf(WalkEntry.Skipped(root / "absolute", Path("absolute"), WalkSkip.LeadsOutside))
        assertEquals(passed, report.passedOver)
        assertEquals(listOf(mirrored("a.txt"), mirrored("relative")), report.copied)
        assertEquals(emptyList(), report.held)
    }

    @Test
    fun `a mirror cuts a hard link in the destination instead of writing through it`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("new")
        val theirs = (dir / "theirs.txt").writeRaw("theirs")
        dst.makeFolders()
        (dst / "a.txt").hardLinkTo(theirs).assertSuccess()

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals("theirs", theirs.readRaw())
        assertEquals("new", (dst / "a.txt").readRaw())
    }

    @Test
    fun `under Inside a mirror copies a link to a file as the file it leads to`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        links.make(root / "link.txt", Path("a.txt"))

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        assertEquals(emptyList(), report.failures)
        assertEquals(Ok(FileType.Regular), (dst / "link.txt").fileType(followLinks = false))
        assertEquals("a", (dst / "link.txt").readRaw())
    }

    @Test
    fun `approveRemovals is told how many files and folders would go and how many entries the destination holds`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        root.mirrorTo(out()).assertSuccess()
        (dst / "old").makeFolders()
        (dst / "old" / "x.txt").writeRaw("x")
        (dst / "stale.txt").writeRaw("s")
        val asked = mutableListOf<RemovalPlan>()

        root.mirrorTo(
            out(),
            approveRemovals = { plan ->
                asked += plan
                plan.removals
            },
        ).assertSuccess()

        val removals = listOf(Path("old", "x.txt"), Path("old"), Path("stale.txt")).map {
            Removal(it, RemovalReason.Stale)
        }
        assertEquals(listOf(RemovalPlan(removals, files = 2, folders = 1, dstEntries = 4, sourceEmpty = false)), asked)
    }

    @Test
    fun `a mirror that did all it planned is clean also when enter refused a folder`() {
        (root / ".git").makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "stale.txt").makeFolders()

        val report = root.mirrorTo(out(), enter = { it.path.name != ".git" }).assertSuccess()

        assertEquals(true, report.isClean)
    }

    @Test
    fun `under Inside a mirror with a failure or a declined removal or a dangling link is not clean`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        (dst / "stale.txt").makeFolders()
        val declined = root.mirrorTo(out(), approveRemovals = { emptyList() })
        (root / "b.txt").writeRaw("b")
        val failing = root.mirrorTo(out(), copy = { _, _, name -> Err(FileError.NotFound(dst / name)) })
        links.make(root / "gone.txt", dir / "missing")
        val dangling = root.mirrorTo(out(), symlinks = Symlinks.Inside)

        assertEquals(false, failing.assertSuccess().isClean)
        assertEquals(false, declined.assertSuccess().isClean)
        assertEquals(false, dangling.assertSuccess().isClean)
    }

    @Test
    fun `an output name a writer refuses is that entry's failure and nothing is written for it`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")

        val report = root.mirrorTo(out(), outputOf = { ".." }).assertSuccess()

        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)
        val refused = FileError.InvalidRelativePath(dst, "..", problem)
        assertEquals(listOf(WalkFailure(root / "a.txt", Path("a.txt"), refused)), report.failures)
        assertEquals(emptyList(), SystemFileSystem.list(dst))
    }

    @Test
    fun `an output name over 255 bytes is that entry's failure naming the output`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        val long = "a".repeat(256)

        val report = root.mirrorTo(out(), outputOf = { long }).assertSuccess()

        val problem = RelativePathProblem.BadName(0, long, NameProblem.TooLong)
        val refused = FileError.InvalidRelativePath(dst.realPath().assertSuccess(), long, problem)
        assertEquals(listOf(WalkFailure(root / "a.txt", Path("a.txt"), refused)), report.failures)
    }

    @Test
    fun `a mirror by default makes a link to a folder as a link and copies the folder once`() {
        (root / "sub").makeFolders()
        (root / "sub" / "s.txt").writeRaw("s")
        links.make(root / "a-link", Path("sub"))

        val report = root.mirrorTo(out()).assertSuccess()

        assertEquals(mapOf("a-link" to "-> sub", "sub" to "dir", "sub/s.txt" to "s"), dst.tree())
        assertEquals(emptyList(), report.held)
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Inside a link to a folder walked under its own path holds nothing and that folder's copy stays`() {
        (root / "sub").makeFolders()
        (root / "sub" / "s.txt").writeRaw("new")
        links.make(root / "a-link", Path("sub"))
        (dst / "sub").makeFolders()
        (dst / "sub" / "s.txt").writeRaw("old")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()

        val walkedFirst = WalkSkip.AlreadyEntered(firstRelative = Path("sub"))
        assertEquals(listOf(Path("a-link") to walkedFirst), report.passedOver.map { it.relative to it.why })
        assertEquals(emptyList(), report.held)
        assertEquals(emptyList(), report.removed)
        assertEquals(mapOf("sub" to "dir", "sub/s.txt" to "new"), dst.tree())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `under Inside the old copy at a link to a file whose target became a folder goes once approved`() {
        (root / "assets").makeFolders()
        (root / "blog").makeFolders()
        (root / "assets" / "logo.png").writeRaw("logo")
        links.make(root / "blog" / "logo-link.png", Path("../assets/logo.png"))
        root.mirrorTo(out(), symlinks = Symlinks.Inside).assertSuccess()
        assertEquals("logo", (dst / "blog" / "logo-link.png").readRaw())
        SystemFileSystem.delete(root / "assets" / "logo.png")
        (root / "assets" / "logo.png").makeFolders()
        (root / "assets" / "logo.png" / "x.png").writeRaw("x")

        val report = root.mirrorTo(out(), symlinks = Symlinks.Inside, approveRemovals = { it.removals }).assertSuccess()

        assertTrue(Path("blog", "logo-link.png") in report.removed, report.toString())
        assertEquals(emptyList(), report.held)
        val tree = mapOf(
            "assets" to "dir",
            "assets/logo.png" to "dir",
            "assets/logo.png/x.png" to "x",
            "blog" to "dir",
        )
        assertEquals(tree, dst.tree())
    }

    @Test
    fun `a FIFO in the source holds nothing and a run with nothing at its place is clean`() {
        root.makeFolders()
        (root / "a.txt").writeRaw("a")
        // Windows has no FIFOs, which leaves nothing to observe.
        if ((root / "pipe").mkfifo() !is Result.Success) return

        val report = root.mirrorTo(out()).assertSuccess()

        val passed = listOf(Path("pipe") to WalkSkip.NotFileOrFolder(FileType.Fifo))
        assertEquals(passed, report.passedOver.map { it.relative to it.why })
        assertEquals(emptyList(), report.held)
        assertEquals(mapOf("a.txt" to "a"), dst.tree())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `a source file named like a leftover that exclude names is passed over and the mirror is clean`() {
        val name = ".notes.md.$A_WRITE_ID.tmp"
        root.makeFolders()
        (root / "notes.md").writeRaw("n")
        (root / name).writeRaw("half")

        val report = root.mirrorTo(out(), exclude = setOf(root / name)).assertSuccess()

        assertEquals(listOf(Path(name) to WalkSkip.Excluded), report.held.map { it.relative to it.why })
        assertEquals(emptyList(), report.failures)
        assertEquals(mapOf("notes.md" to "n"), dst.tree())
        assertTrue(report.isClean, report.toString())
    }

    @Test
    fun `a mirror under AsLinks does not make a link exclude names and copies the folder it leads to`() {
        (root / "releases" / "v2").makeFolders()
        (root / "releases" / "v2" / "app.txt").writeRaw("app")
        links.make(root / "current", Path("releases", "v2"))

        val report = root.mirrorTo(out(), exclude = setOf(root / "current")).assertSuccess()

        val tree = mapOf("releases" to "dir", "releases/v2" to "dir", "releases/v2/app.txt" to "app")
        assertEquals(tree, dst.tree())
        assertEquals(listOf(Path("current") to WalkSkip.Excluded), report.held.map { it.relative to it.why })
        assertTrue(report.isClean, report.toString())
    }
}
