package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class WalkTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    @Test
    fun `each directory reports its own files first and then walks its subdirectories in name order`() {
        createDirectoriesRaw(dir / "b" / "d")
        createDirectoriesRaw(dir / "e")
        (dir / "c.txt").writeRaw("c")
        (dir / "a.txt").writeRaw("a")
        (dir / "b" / "x.txt").writeRaw("x")
        (dir / "b" / "d" / "y.txt").writeRaw("y")
        // `e` holds a file so the order tells a depth-first walk from a breadth-first one: `b`'s own tree is
        // finished before `e` is entered, and `b` is entered first because the subdirectories go in name order.
        (dir / "e" / "z.txt").writeRaw("z")

        val expected = listOf(
            dir / "a.txt",
            dir / "c.txt",
            dir / "b" / "x.txt",
            dir / "b" / "d" / "y.txt",
            dir / "e" / "z.txt",
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().walkedFiles())
    }

    @Test
    fun `each folder below the root is yielded as it is entered just before its own entries`() {
        createDirectoriesRaw(dir / "b" / "d")
        createDirectoriesRaw(dir / "e")
        (dir / "a.txt").writeRaw("a")
        (dir / "b" / "x.txt").writeRaw("x")
        (dir / "b" / "d" / "y.txt").writeRaw("y")

        val expected = listOf(
            walkedFile(dir / "a.txt", Path("a.txt"), size = 1, viaLink = false),
            WalkEntry.Folder(dir / "b", Path("b")),
            walkedFile(dir / "b" / "x.txt", Path("b") / "x.txt", size = 1, viaLink = false),
            WalkEntry.Folder(dir / "b" / "d", Path("b") / "d"),
            walkedFile(dir / "b" / "d" / "y.txt", Path("b") / "d" / "y.txt", size = 1, viaLink = false),
            WalkEntry.Folder(dir / "e", Path("e")),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `a missing root is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.walkTopDown())
    }

    @Test
    fun `name order compares code units so a dot comes first and capitals before small letters`() {
        val hidden = (dir / ".hidden").writeRaw("h")
        val small = (dir / "a.txt").writeRaw("a")
        val capital = (dir / "B.txt").writeRaw("b")

        assertEquals(listOf(hidden, capital, small).map { Ok(it) }, dir.walkTopDown().assertSuccess().walkedFiles())
    }

    @Test
    fun `the root is listed when the walk is asked for and what lies below it as the walk goes`() {
        val early = (dir / "a.txt").writeRaw("a")
        createDirectoriesRaw(dir / "sub")
        val walk = dir.walkTopDown().assertSuccess()
        (dir / "late.txt").writeRaw("l")
        val below = (dir / "sub" / "b.txt").writeRaw("b")

        assertEquals(listOf(Ok(early), Ok(below)), walk.walkedFiles())
    }

    @Test
    fun `walking the same answer again lists the root afresh`() {
        val early = (dir / "a.txt").writeRaw("a")
        val walk = dir.walkTopDown().assertSuccess()
        assertEquals(listOf(Ok(early)), walk.walkedFiles())
        val late = (dir / "b.txt").writeRaw("b")

        assertEquals(listOf(Ok(early), Ok(late)), walk.walkedFiles())
    }

    @Test
    fun `a folder below that cannot be listed is a WalkFailure naming it and where it sits below the root`() {
        val locked = dir / "a" / "locked"
        createDirectoriesRaw(locked)

        locked.whileUnlistable {
            val failure = dir
                .walkTopDown()
                .assertSuccess()
                .mapNotNull { it.errorOrNull() }
                .single()

            assertEquals(locked, failure.path)
            assertEquals(Path("a", "locked"), failure.relative)
            assertEquals(IoCause.Denied, assertIs<FileError.Inaccessible>(failure.error).cause)
            assertEquals(failure.path, dir / failure.relative)
        }
    }

    @Test
    fun `a root gone before the walk is iterated a second time is one WalkFailure naming the root`() {
        val root = dir / "root"
        createDirectoriesRaw(root)
        val walk = root.walkTopDown().assertSuccess()
        assertEquals(emptyList(), walk.toList())
        SystemFileSystem.delete(root)

        val failure = WalkFailure(root, Path(""), FileError.NotFound(root))
        assertEquals(listOf(Err(failure)), walk.toList())
    }

    @Test
    fun `each entry carries its path below the root and nothing passed over yields no skipped entry`() {
        createDirectoriesRaw(dir / "b")
        (dir / "a.txt").writeRaw("a")
        (dir / "b" / "x.txt").writeRaw("x")

        val expected = listOf(
            walkedFile(dir / "a.txt", Path("a.txt"), size = 1, viaLink = false),
            WalkEntry.Folder(dir / "b", Path("b")),
            walkedFile(dir / "b" / "x.txt", Path("b") / "x.txt", size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `a regular file root is one entry whose relative path is its name`() {
        val file = (dir / "a.txt").writeRaw("a")

        val expected = walkedFile(file, Path("a.txt"), size = 1, viaLink = false)
        assertEquals(listOf(Ok(expected)), file.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `an entry gone between the listing and its lookup is skipped as Gone`() {
        val gone = (dir / "a.txt").writeRaw("a")
        val kept = (dir / "b.txt").writeRaw("b")
        val walk = dir.walkTopDown().assertSuccess()
        SystemFileSystem.delete(gone)

        val expected = listOf(
            WalkEntry.Skipped(gone, Path("a.txt"), WalkSkip.Gone),
            walkedFile(kept, Path("b.txt"), size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walk.toList())
    }

    @Test
    fun `a folder gone between its lookup and its listing is skipped as Gone and not yielded as a Folder`() {
        createDirectoriesRaw(dir / "b")
        (dir / "b" / "x.txt").writeRaw("x")
        val after = (dir / "c.txt").writeRaw("c")
        val walk = dir.walkTopDown().assertSuccess().iterator()
        assertEquals(Ok(walkedFile(after, Path("c.txt"), size = 1, viaLink = false)), walk.next())

        (dir / "b").deleteRecursively().assertSuccess()

        assertEquals(Ok(WalkEntry.Skipped(dir / "b", Path("b"), WalkSkip.Gone)), walk.next())
        assertFalse(walk.hasNext())
    }

    @Test
    fun `a file entry carries its size and whether a symlink led to it`() {
        val file = (dir / "a.txt").writeRaw("abc")
        val link = (dir / "b.txt").linkTo(file)

        val expected = listOf(
            walkedFile(file, Path("a.txt"), size = 3, viaLink = false),
            walkedFile(link, Path("b.txt"), size = 3, viaLink = true),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown().assertSuccess().toList())
    }

    @Test
    fun `a file entry carries the modification time info reads also for a file a symlink leads to`() {
        val file = (dir / "a.txt").writeRaw("abc")
        val old = Instant.parse("2001-02-03T04:05:06Z")
        openFileSink(file, append = true).use { it.setModifiedTime(old) }
        val link = (dir / "b.txt").linkTo(file)

        val times = dir
            .walkTopDown()
            .assertSuccess()
            .map { (it.assertSuccess() as WalkEntry.File).modifiedAt }
            .toList()

        assertEquals(listOf(old, old), times)
        assertEquals(listOf(file.info(), link.info()).map { it.assertSuccess().modifiedAt }, times)
    }

    @Test
    fun `a folder enter refuses is skipped as Refused and never read while the root is not asked`() {
        createDirectoriesRaw(dir / "a")
        createDirectoriesRaw(dir / "b")
        (dir / "a" / "x").writeRaw("x")
        val kept = (dir / "b" / "y").writeRaw("y")
        val asked = mutableListOf<WalkEntry.Folder>()

        val walked = dir
            .walkTopDown { folder ->
                asked += folder
                folder.relative != Path("a")
            }
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.Refused),
            WalkEntry.Folder(dir / "b", Path("b")),
            walkedFile(kept, Path("b") / "y", size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
        assertEquals(listOf(WalkEntry.Folder(dir / "a", Path("a")), WalkEntry.Folder(dir / "b", Path("b"))), asked)
    }

    @Test
    fun `what enter throws ends the iteration after the entries yielded before it`() {
        val file = (dir / "a.txt").writeRaw("a")
        createDirectoriesRaw(dir / "b")
        val yielded = mutableListOf<Path>()

        val thrown = assertFailsWith<IllegalStateException> {
            dir
                .walkTopDown(enter = { error("refused ${it.relative}") })
                .assertSuccess()
                .forEach { yielded += it.assertSuccess().path }
        }

        assertEquals("refused b", thrown.message)
        assertEquals(listOf(file), yielded)
    }

    @Test
    fun `a link to a folder already walked names the relative path that folder was walked under`() {
        val real = dir / "c"
        createDirectoriesRaw(real)
        (real / "x").writeRaw("x")
        (dir / "a").linkTo(real)
        (real / "up").linkTo(dir)

        val expected = listOf(
            WalkEntry.Folder(real, Path("c")),
            walkedFile(real / "x", Path("c") / "x", size = 1, viaLink = false),
            WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.AlreadyEntered(firstRelative = Path("c"))),
            WalkEntry.Skipped(real / "up", Path("c") / "up", WalkSkip.AlreadyEntered(firstRelative = null)),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown(Symlinks.Anywhere).assertSuccess().toList())
    }

    @Test
    fun `a folder enter refuses under one path is walked under the next path that reaches it`() {
        val real = dir / "c"
        createDirectoriesRaw(real)
        (real / "x").writeRaw("x")
        (dir / "a").linkTo(real)

        val walked = dir
            .walkTopDown(Symlinks.Anywhere) { it.relative != Path("c") }
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(real, Path("c"), WalkSkip.Refused),
            WalkEntry.Folder(dir / "a", Path("a")),
            walkedFile(dir / "a" / "x", Path("a") / "x", size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `an entry below a followed link names the path under the link the walk took`() {
        val root = dir / "root"
        val real = dir / "c"
        createDirectoriesRaw(root)
        createDirectoriesRaw(real)
        (real / "loop").linkTo(Path("loop"))
        (root / "a").linkTo(real)

        val skipped = root
            .walkTopDown(Symlinks.Anywhere)
            .assertSuccess()
            .map { it.assertSuccess() }
            .filterIsInstance<WalkEntry.Skipped>()
            .single { it.why == WalkSkip.LinkLoop }

        assertEquals(Path("a") / "loop", skipped.relative)
        assertEquals(root / "a" / "loop", skipped.path)
    }

    @Test
    fun `a folder link back to an ancestor under Anywhere is AlreadyEntered rather than LinkLoop`() {
        createDirectoriesRaw(dir / "a")
        (dir / "a" / "up").linkTo(Path(".."))

        val skipped = dir
            .walkTopDown(Symlinks.Anywhere)
            .assertSuccess()
            .map { it.assertSuccess() }
            .filterIsInstance<WalkEntry.Skipped>()
            .single()

        assertEquals(WalkEntry.Skipped(dir / "a" / "up", Path("a") / "up", WalkSkip.AlreadyEntered(null)), skipped)
    }

    @Test
    fun `Inside walks links to a folder and to a file inside the root as Anywhere does`() {
        val root = dir / "root"
        createDirectoriesRaw(root / "real")
        (root / "real" / "x.txt").writeRaw("x")
        (root / "a").linkTo(root / "real")
        (root / "y.txt").linkTo(root / "real" / "x.txt")

        val inside = root.walkTopDown(Symlinks.Inside).assertSuccess().toList()

        assertEquals(root.walkTopDown(Symlinks.Anywhere).assertSuccess().toList(), inside)
        assertEquals(Ok(WalkEntry.Skipped(root / "a", Path("a"), WalkSkip.AlreadyEntered(Path("real")))), inside[3])
    }

    @Test
    fun `Inside passes over a link to a folder or a file outside the root as LeadsOutside`() {
        val root = dir / "root"
        val outside = dir / "outside"
        createDirectoriesRaw(root)
        createDirectoriesRaw(outside)
        (outside / "f.txt").writeRaw("f")
        (root / "f.txt").linkTo(outside / "f.txt")
        (root / "out").linkTo(outside)

        val expected = listOf(
            Ok(WalkEntry.Skipped(root / "f.txt", Path("f.txt"), WalkSkip.LeadsOutside)),
            Ok(WalkEntry.Skipped(root / "out", Path("out"), WalkSkip.LeadsOutside)),
        )
        assertEquals(expected, root.walkTopDown(Symlinks.Inside).assertSuccess().toList())
    }

    @Test
    fun `Inside follows a chain of links that leaves the root and comes back by where it ends`() {
        val root = dir / "root"
        createDirectoriesRaw(root / "real")
        (root / "real" / "x.txt").writeRaw("x")
        (dir / "hop").linkTo(root / "real")
        (root / "back").linkTo(dir / "hop")

        val expected = listOf(
            Ok(WalkEntry.Folder(root / "real", Path("real"))),
            Ok(walkedFile(root / "real" / "x.txt", Path("real/x.txt"), 1, viaLink = false)),
            Ok(WalkEntry.Skipped(root / "back", Path("back"), WalkSkip.AlreadyEntered(Path("real")))),
        )
        assertEquals(expected, root.walkTopDown(Symlinks.Inside).assertSuccess().toList())
    }

    @Test
    fun `the default walk follows links inside the root and passes over one leading outside`() {
        val root = dir / "root"
        val outside = dir / "outside"
        createDirectoriesRaw(root / "real")
        createDirectoriesRaw(outside)
        (root / "real" / "x.txt").writeRaw("x")
        (outside / "secret.txt").writeRaw("s")
        (root / "a").linkTo(root / "real")
        (root / "leak.txt").linkTo(outside / "secret.txt")

        val walked = root.walkTopDown().assertSuccess().toList()

        assertEquals(root.walkTopDown(Symlinks.Inside).assertSuccess().toList(), walked)
        val expected = listOf(
            Ok(WalkEntry.Skipped(root / "leak.txt", Path("leak.txt"), WalkSkip.LeadsOutside)),
            Ok(WalkEntry.Folder(root / "real", Path("real"))),
            Ok(walkedFile(root / "real" / "x.txt", Path("real/x.txt"), 1, viaLink = false)),
            Ok(WalkEntry.Skipped(root / "a", Path("a"), WalkSkip.AlreadyEntered(Path("real")))),
        )
        assertEquals(expected, walked)
    }

    @Test
    fun `Inside follows a link that leaves the root and comes back in`() {
        val state = dir / "state"
        createDirectoriesRaw(state)
        (state / "b").writeRaw("b")
        (state / "a").linkTo(Path("../state/b"))

        val expected = listOf(
            Ok(walkedFile(state / "a", Path("a"), 1, viaLink = true)),
            Ok(walkedFile(state / "b", Path("b"), 1, viaLink = false)),
        )
        assertEquals(expected, state.walkTopDown(Symlinks.Inside).assertSuccess().toList())
    }

    @Test
    fun `NoLinks passes over every link as Link whatever it leads to`() {
        createDirectoriesRaw(dir / "real")
        (dir / "real" / "x.txt").writeRaw("x")
        (dir / "file").linkTo(dir / "real" / "x.txt")
        (dir / "folder").linkTo(dir / "real")
        (dir / "nowhere").linkTo(dir / "missing")

        val expected = listOf(
            WalkEntry.Skipped(dir / "file", Path("file"), WalkSkip.Link),
            WalkEntry.Skipped(dir / "folder", Path("folder"), WalkSkip.Link),
            WalkEntry.Skipped(dir / "nowhere", Path("nowhere"), WalkSkip.Link),
            WalkEntry.Folder(dir / "real", Path("real")),
            walkedFile(dir / "real" / "x.txt", Path("real/x.txt"), 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown(Symlinks.NoLinks).assertSuccess().toList())
    }

    @Test
    fun `AsLinks yields every link as a Link holding its target as written and follows none`() {
        createDirectoriesRaw(dir / "real")
        (dir / "real" / "x.txt").writeRaw("x")
        (dir / "file").linkTo(Path("real/x.txt"))
        (dir / "folder").linkTo(Path("real"))
        (dir / "nowhere").linkTo(Path("missing"))
        (dir / "out").linkTo(dir / "real")

        val expected = listOf(
            WalkEntry.Link(dir / "file", Path("file"), Path("real/x.txt")),
            WalkEntry.Link(dir / "folder", Path("folder"), Path("real")),
            WalkEntry.Link(dir / "nowhere", Path("nowhere"), Path("missing")),
            WalkEntry.Link(dir / "out", Path("out"), dir / "real"),
            WalkEntry.Folder(dir / "real", Path("real")),
            walkedFile(dir / "real" / "x.txt", Path("real/x.txt"), 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, dir.walkTopDown(Symlinks.AsLinks).assertSuccess().toList())
    }

    @Test
    fun `a folder in exclude is skipped as Excluded and never read while enter is not asked about it`() {
        createDirectoriesRaw(dir / "a")
        createDirectoriesRaw(dir / "b")
        (dir / "a" / "x").writeRaw("x")
        val kept = (dir / "b" / "y").writeRaw("y")
        val asked = mutableListOf<Path>()

        val walked = dir
            .walkTopDown(exclude = setOf(dir / "a")) { folder ->
                asked += folder.relative
                true
            }.assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.Excluded),
            WalkEntry.Folder(dir / "b", Path("b")),
            walkedFile(kept, Path("b") / "y", size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
        assertEquals(listOf(Path("b")), asked)
    }

    @Test
    fun `exclude matches a folder by its real path whichever way either is spelled`() {
        createDirectoriesRaw(dir / "a" / "deep")
        (dir / "a" / "deep" / "x").writeRaw("x")
        val spelledThroughLink = dir / "via" / "deep"
        (dir / "via").linkTo(dir / "a")

        val walked = dir
            .walkTopDown(Symlinks.NoLinks, exclude = setOf(spelledThroughLink))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "via", Path("via"), WalkSkip.Link),
            WalkEntry.Folder(dir / "a", Path("a")),
            WalkEntry.Skipped(dir / "a" / "deep", Path("a") / "deep", WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under Anywhere a link to an excluded folder is Excluded as the folder is`() {
        createDirectoriesRaw(dir / "c")
        (dir / "c" / "x").writeRaw("x")
        (dir / "a").linkTo(dir / "c")

        val walked = dir
            .walkTopDown(Symlinks.Anywhere, exclude = setOf(dir / "c"))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.Excluded),
            WalkEntry.Skipped(dir / "c", Path("c"), WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under Inside a link to an excluded folder is Excluded as the folder is`() {
        createDirectoriesRaw(dir / "c")
        (dir / "c" / "x").writeRaw("x")
        (dir / "a").linkTo(dir / "c")

        val walked = dir
            .walkTopDown(Symlinks.Inside, exclude = setOf(dir / "c"))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.Excluded),
            WalkEntry.Skipped(dir / "c", Path("c"), WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under AsLinks a link to an excluded folder or a file in one is yielded as a Link`() {
        createDirectoriesRaw(dir / "c")
        (dir / "c" / "x").writeRaw("x")
        (dir / "a").linkTo(dir / "c")
        (dir / "f").linkTo(dir / "c" / "x")

        val walked = dir
            .walkTopDown(Symlinks.AsLinks, exclude = setOf(dir / "c"))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Link(dir / "a", Path("a"), dir / "c"),
            WalkEntry.Skipped(dir / "c", Path("c"), WalkSkip.Excluded),
            WalkEntry.Link(dir / "f", Path("f"), dir / "c" / "x"),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    /** Walks [dir] holding `releases/v2/app.txt` and `current -> releases/v2` with `current` excluded. */
    private fun walkWithCurrentExcluded(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        createDirectoriesRaw(dir / "releases" / "v2")
        (dir / "releases" / "v2" / "app.txt").writeRaw("app")
        (dir / "current").linkTo(Path("releases", "v2"))
        return dir
            .walkTopDown(symlinks, exclude = setOf(dir / "current"))
            .assertSuccess()
            .toList()
    }

    /** What [walkWithCurrentExcluded] yields where `current` is passed over by its own place. */
    private fun currentExcludedReleasesWalked(): List<Result<WalkEntry, WalkFailure>> = listOf(
        WalkEntry.Skipped(dir / "current", Path("current"), WalkSkip.Excluded),
        WalkEntry.Folder(dir / "releases", Path("releases")),
        WalkEntry.Folder(dir / "releases" / "v2", Path("releases", "v2")),
        walkedFile(dir / "releases" / "v2" / "app.txt", Path("releases", "v2", "app.txt"), size = 3, viaLink = false),
    ).map { Ok(it) }

    @Test
    fun `under NoLinks an excluded link is Excluded by its own place and what it leads to is walked`() {
        val walked = walkWithCurrentExcluded(Symlinks.NoLinks)

        assertEquals(currentExcludedReleasesWalked(), walked)
    }

    @Test
    fun `under AsLinks an excluded link is Excluded by its own place and what it leads to is walked`() {
        val walked = walkWithCurrentExcluded(Symlinks.AsLinks)

        assertEquals(currentExcludedReleasesWalked(), walked)
    }

    @Test
    fun `under Inside an excluded link excludes the folder it leads to`() {
        val walked = walkWithCurrentExcluded(Symlinks.Inside)

        val expected = listOf(
            WalkEntry.Skipped(dir / "current", Path("current"), WalkSkip.Excluded),
            WalkEntry.Folder(dir / "releases", Path("releases")),
            WalkEntry.Skipped(dir / "releases" / "v2", Path("releases", "v2"), WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    /** Walks [dir] with `out` excluded and `src/assets` a link to the folder `out/assets` below it. */
    private fun walkFolderLinkBelowExcluded(symlinks: Symlinks) {
        createDirectoriesRaw(dir / "out" / "assets")
        (dir / "out" / "assets" / "logo.png").writeRaw("png")
        createDirectoriesRaw(dir / "src")
        val main = (dir / "src" / "main.kt").writeRaw("fun main() {}")
        (dir / "src" / "assets").linkTo(dir / "out" / "assets")

        val walked = dir
            .walkTopDown(symlinks, exclude = setOf(dir / "out"))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "out", Path("out"), WalkSkip.Excluded),
            WalkEntry.Folder(dir / "src", Path("src")),
            WalkEntry.Skipped(dir / "src" / "assets", Path("src") / "assets", WalkSkip.Excluded),
            walkedFile(main, Path("src") / "main.kt", size = 13, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under Anywhere a link to a folder below an excluded folder is Excluded and nothing below it is yielded`() {
        walkFolderLinkBelowExcluded(Symlinks.Anywhere)
    }

    @Test
    fun `under Inside a link to a folder below an excluded folder is Excluded and nothing below it is yielded`() {
        walkFolderLinkBelowExcluded(Symlinks.Inside)
    }

    /** Walks [dir] holding links to files in and below the excluded `c`, and one to a file outside it. */
    private fun walkFileLinksIntoExcluded(symlinks: Symlinks) {
        createDirectoriesRaw(dir / "c" / "deep")
        (dir / "c" / "x").writeRaw("x")
        (dir / "c" / "deep" / "y").writeRaw("y")
        val kept = (dir / "keep.txt").writeRaw("k")
        (dir / "f").linkTo(dir / "c" / "x")
        (dir / "g").linkTo(dir / "c" / "deep" / "y")
        val outside = (dir / "h").linkTo(kept)

        val walked = dir
            .walkTopDown(symlinks, exclude = setOf(dir / "c"))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(dir / "c", Path("c"), WalkSkip.Excluded),
            WalkEntry.Skipped(dir / "f", Path("f"), WalkSkip.Excluded),
            WalkEntry.Skipped(dir / "g", Path("g"), WalkSkip.Excluded),
            walkedFile(outside, Path("h"), size = 1, viaLink = true),
            walkedFile(kept, Path("keep.txt"), size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under Anywhere a link to a file in or below an excluded folder is Excluded`() {
        walkFileLinksIntoExcluded(Symlinks.Anywhere)
    }

    @Test
    fun `under Inside a link to a file in or below an excluded folder is Excluded`() {
        walkFileLinksIntoExcluded(Symlinks.Inside)
    }

    @Test
    fun `a file in exclude is skipped as Excluded and so is a link to it`() {
        val skipped = (dir / "a.tmp").writeRaw("a")
        val kept = (dir / "b.txt").writeRaw("b")
        (dir / "c.tmp").linkTo(skipped)

        val walked = dir
            .walkTopDown(exclude = setOf(skipped))
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(skipped, Path("a.tmp"), WalkSkip.Excluded),
            walkedFile(kept, Path("b.txt"), size = 1, viaLink = false),
            WalkEntry.Skipped(dir / "c.tmp", Path("c.tmp"), WalkSkip.Excluded),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `an excluded path nothing is at excludes nothing`() {
        createDirectoriesRaw(dir / "a")

        val walked = dir
            .walkTopDown(exclude = setOf(dir / "missing"))
            .assertSuccess()
            .toList()

        assertEquals(listOf(Ok(WalkEntry.Folder(dir / "a", Path("a")))), walked)
    }

    @Test
    fun `an excluded path that cannot be resolved is the walk's error`() {
        val invalid = dir / "a\u0000b"

        assertEquals(Err(FileError.InvalidName(invalid, NameProblem.Nul)), dir.walkTopDown(exclude = setOf(invalid)))
    }
}
