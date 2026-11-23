package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * What a walk makes of an entry that changes between two of its lookups, which is what the entry was before the change
 * or what it is after it, and how many lookups the walk makes.
 */
class WalkLookupsTest {
    private val dir = newTempDir()
    private val outside = newTempDir()
    private val links = TestLinks()
    private val lookups = RecordingLookups()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
        outside.deleteTree()
        assertEquals(emptyList(), lookups.changesNotMade(), "a change the test set up was never made")
    }

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    private fun walk(
        symlinks: Symlinks,
        exclude: Set<Path> = emptySet(),
        through: WalkLookups = lookups,
    ): List<Result<WalkEntry, WalkFailure>> = dir.walkTopDownWith(through, symlinks, exclude).assertSuccess().toList()

    /** What [walked] holds at [path] or below it. */
    private fun at(
        path: Path,
        walked: List<Result<WalkEntry, WalkFailure>>,
    ): List<Result<WalkEntry, WalkFailure>> = walked.filter { step ->
        val at = when (step) {
            is Result.Success -> step.value.path
            is Result.Error -> step.error.path
        }
        at.isAtOrBelow(path)
    }

    private fun Path.becomeFile(content: String) {
        deleteLink(this)?.let { throw it }
        writeRaw(content)
    }

    private fun Path.becomeFolderHolding(name: String) {
        deleteLink(this)?.let { throw it }
        createDirectoriesRaw(this)
        (this / name).writeRaw("x")
    }

    private fun Path.becomeLink(target: Path) {
        delete().assertSuccess()
        createLink(this, target)?.let { throw it }
    }

    private fun Path.becomeEmptyFolder() {
        deleteLink(this)?.let { throw it }
        createDirectoriesRaw(this)
    }

    private fun Path.retarget(target: Path) {
        deleteLink(this)?.let { throw it }
        createLink(this, target)?.let { throw it }
    }

    private fun assertOneOf(
        allowed: List<Any>,
        actual: Any?,
    ) = assertTrue(actual in allowed, "expected one of $allowed but was $actual")

    @Test
    fun `under AsLinks a link swapped for a file before the walk looks at it is yielded as that file`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.beforeFirst(link) { link.becomeFile("xy") }

        val walked = walk(Symlinks.AsLinks)

        assertEquals(listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))), walked)
    }

    @Test
    fun `under AsLinks a file swapped for a link before the walk looks at it is yielded as that link`() {
        val file = (dir / "a").writeRaw("x")
        lookups.beforeFirst(file) { file.becomeLink(Path("missing")) }

        assertEquals(listOf(Ok(WalkEntry.Link(file, Path("a"), Path("missing")))), walk(Symlinks.AsLinks))
    }

    @Test
    fun `under AsLinks a link swapped for a file once the walk saw it as a link is yielded as that file`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.afterFirst(link) { link.becomeFile("xy") }

        val walked = walk(Symlinks.AsLinks)

        assertEquals(listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))), walked)
    }

    @Test
    fun `under AsLinks a link swapped for a folder once the walk saw it as a link is entered as that folder`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.afterFirst(link) { link.becomeFolderHolding("x") }

        val walked = walk(Symlinks.AsLinks)

        val expected = listOf(
            WalkEntry.Folder(link, Path("a")),
            walkedFile(link / "x", Path("a/x"), size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under AsLinks a link removed once the walk saw it as a link is skipped as Gone`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.afterFirst(link) { deleteLink(link)?.let { throw it } }

        assertEquals(listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.Gone))), walk(Symlinks.AsLinks))
    }

    @Test
    fun `under AsLinks a link swapped for a file just before its target is read is yielded as that file`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.before(Lookup.LinkTarget, link) { link.becomeFile("xy") }

        val walked = walk(Symlinks.AsLinks)

        assertEquals(listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))), walked)
    }

    @Test
    fun `under AsLinks a link removed just before its target is read is skipped as Gone`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.before(Lookup.LinkTarget, link) { deleteLink(link)?.let { throw it } }

        assertEquals(listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.Gone))), walk(Symlinks.AsLinks))
    }

    @Test
    fun `under AsLinks a link swapped for a file just before its target is read is looked at once more as itself`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.before(Lookup.LinkTarget, link) { link.becomeFile("xy") }

        walk(Symlinks.AsLinks)

        assertEquals(listOf(Lookup.OwnInfo, Lookup.LinkTarget, Lookup.OwnInfo), lookups.of(link))
    }

    @Test
    fun `under AsLinks a link that is a file as its target is read and a link to a folder after is never entered`() {
        createDirectoriesRaw(dir / "elsewhere")
        (dir / "elsewhere" / "x").writeRaw("x")
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.before(Lookup.LinkTarget, link) { link.becomeFile("xy") }
        // Whichever lookup tells the walk what is there after the failed read sees the file, then it is a link again.
        var swappedBack = false
        val swapBack: (Path) -> Unit = { path ->
            if (path == link && !swappedBack) {
                swappedBack = true
                link.becomeLink(dir / "elsewhere")
            }
        }
        val through = object : WalkLookups by lookups {
            override fun isLink(path: Path) = lookups.isLink(path).also { swapBack(path) }

            override fun ownInfo(path: Path) = lookups.ownInfo(path).also {
                if (lookups.of(path).count { it == Lookup.OwnInfo } == 2) swapBack(path)
            }
        }

        // The file is gone by now, so its time is left out.
        val walked = at(link, walk(Symlinks.AsLinks, through = through)).timeless()

        val allowed = listOf(
            listOf(Ok(WalkEntry.File(link, Path("a"), size = 2, modifiedAt = Instant.DISTANT_PAST, viaLink = false))),
            listOf(Ok(WalkEntry.Link(link, Path("a"), Path("missing")))),
            listOf(Ok(WalkEntry.Link(link, Path("a"), dir / "elsewhere"))),
        )
        assertOneOf(allowed, walked)
    }

    @Test
    fun `under Inside a link to an excluded file retargeted once the walk resolved it is excluded or the new file`() {
        val excluded = (dir / "t").writeRaw("abc")
        (dir / "u").writeRaw("x")
        val link = (dir / "a").linkTo(Path("t"))
        lookups.after(Lookup.OneName, link) { link.becomeLink(Path("u")) }

        val walked = at(link, walk(Symlinks.Inside, exclude = setOf(excluded)))

        val allowed = listOf(
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.Excluded))),
            listOf(Ok(walkedFile(link, Path("a"), size = 1, viaLink = true))),
        )
        assertOneOf(allowed, walked)
    }

    /**
     * What the walk yields at `a`, an excluded file swapped for a link to the file `t` once the walk looked at it and
     * saw it is no link, in [symlinks] mode.
     */
    private fun excludedFileSwappedForALink(
        symlinks: Symlinks,
        target: Path = Path("t"),
    ): List<Result<WalkEntry, WalkFailure>> {
        (dir / "t").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        lookups.after(Lookup.OwnInfo, file) { file.becomeLink(target) }
        return at(file, walk(symlinks, exclude = setOf(file)))
    }

    private fun excludedAt(path: Path) = listOf(Ok(WalkEntry.Skipped(path, Path(path.name), WalkSkip.Excluded)))

    @Test
    fun `under AsLinks an excluded file swapped for a link once the walk looked at it is excluded`() {
        assertEquals(excludedAt(dir / "a"), excludedFileSwappedForALink(Symlinks.AsLinks))
    }

    @Test
    fun `under NoLinks an excluded file swapped for a link once the walk looked at it is excluded`() {
        assertEquals(excludedAt(dir / "a"), excludedFileSwappedForALink(Symlinks.NoLinks))
    }

    @Test
    fun `under AsLinks an excluded file swapped for a dangling link once the walk looked at it is excluded`() {
        assertEquals(excludedAt(dir / "a"), excludedFileSwappedForALink(Symlinks.AsLinks, target = Path("missing")))
    }

    @Test
    fun `under NoLinks an excluded file swapped for a dangling link once the walk looked at it is excluded`() {
        assertEquals(excludedAt(dir / "a"), excludedFileSwappedForALink(Symlinks.NoLinks, target = Path("missing")))
    }

    @Test
    fun `under Inside an excluded file swapped for a link once seen as no link is excluded or the link`() {
        val walked = excludedFileSwappedForALink(Symlinks.Inside)

        val file = dir / "a"
        val asLink = listOf(Ok(walkedFile(file, Path("a"), size = 3, viaLink = true)))
        assertOneOf(listOf(excludedAt(file), asLink), walked)
    }

    @Test
    fun `under Anywhere an excluded file swapped for a link once seen as no link is excluded or the link`() {
        val walked = excludedFileSwappedForALink(Symlinks.Anywhere)

        val file = dir / "a"
        val asLink = listOf(Ok(walkedFile(file, Path("a"), size = 3, viaLink = true)))
        assertOneOf(listOf(excludedAt(file), asLink), walked)
    }

    @Test
    fun `under AsLinks a file swapped for a link to an excluded namesake once looked at is the file or the link`() {
        createDirectoriesRaw(dir / "c")
        val excluded = (dir / "c" / "a").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        lookups.after(Lookup.OwnInfo, file) { file.becomeLink(Path("c/a")) }

        // The file is gone by now, so its time is left out.
        val walked = at(file, walk(Symlinks.AsLinks, exclude = setOf(excluded))).timeless()

        val before = WalkEntry.File(file, Path("a"), size = 1, modifiedAt = Instant.DISTANT_PAST, viaLink = false)
        val after = WalkEntry.Link(file, Path("a"), Path("c/a"))
        assertOneOf(listOf(listOf(Ok(before)), listOf(Ok(after))), walked)
    }

    /**
     * What the walk yields at `a`, a link to the folder `b` waiting for its turn that is swapped for a file at the
     * excluded `a` once `b` is walked, in [symlinks] mode.
     */
    private fun waitingLinkSwappedForAnExcludedFile(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        createDirectoriesRaw(dir / "b")
        (dir / "b" / "y").writeRaw("y")
        val excluded = (dir / "a").writeRaw("x")
        val walk = dir.walkTopDownWith(lookups, symlinks, exclude = setOf(excluded)).assertSuccess().iterator()
        excluded.becomeLink(Path("b"))
        assertEquals(Ok(WalkEntry.Folder(dir / "b", Path("b"))), walk.next())
        assertEquals(Ok(walkedFile(dir / "b" / "y", Path("b/y"), size = 1, viaLink = false)), walk.next())

        excluded.becomeFile("xy")

        return walk.asSequence().toList()
    }

    @Test
    fun `under Inside a waiting link swapped for an excluded file before its turn is Excluded at its turn`() {
        assertEquals(excludedAt(dir / "a"), waitingLinkSwappedForAnExcludedFile(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere a waiting link swapped for an excluded file before its turn is Excluded at its turn`() {
        assertEquals(excludedAt(dir / "a"), waitingLinkSwappedForAnExcludedFile(Symlinks.Anywhere))
    }

    /**
     * What the walk yields at the empty folder `b` or below it, in [symlinks] mode, once `b` is swapped for a link to
     * the folder `g` holding the excluded `x`, after the walk judged `b` and before it lists it.
     */
    private fun folderSwappedForALinkBeforeItIsListed(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        val folder = dir / "b"
        createDirectoriesRaw(folder)
        createDirectoriesRaw(dir / "g")
        val excluded = (dir / "g" / "x").writeRaw("x")
        lookups.after(Lookup.OneName, folder) { folder.becomeLink(Path("g")) }

        return at(folder, walk(symlinks, exclude = setOf(excluded)))
    }

    private val emptyFolderB = listOf(Ok(WalkEntry.Folder(dir / "b", Path("b"))))

    private fun bSkippedAs(why: WalkSkip) = listOf(Ok(WalkEntry.Skipped(dir / "b", Path("b"), why)))

    @Test
    fun `under Inside a folder swapped for a link before it is listed is the empty folder or a link already entered`() {
        val after = bSkippedAs(WalkSkip.AlreadyEntered(Path("g")))
        assertOneOf(listOf(emptyFolderB, after), folderSwappedForALinkBeforeItIsListed(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere a folder swapped for a link before it is listed is the empty folder or a link already entered`() {
        val after = bSkippedAs(WalkSkip.AlreadyEntered(Path("g")))
        assertOneOf(listOf(emptyFolderB, after), folderSwappedForALinkBeforeItIsListed(Symlinks.Anywhere))
    }

    @Test
    fun `under AsLinks a folder swapped for a link before it is listed is the empty folder or the link`() {
        val after = listOf(Ok(WalkEntry.Link(dir / "b", Path("b"), Path("g"))))
        assertOneOf(listOf(emptyFolderB, after), folderSwappedForALinkBeforeItIsListed(Symlinks.AsLinks))
    }

    @Test
    fun `under NoLinks a folder swapped for a link before it is listed is the empty folder or a passed over link`() {
        val after = bSkippedAs(WalkSkip.Link)
        assertOneOf(listOf(emptyFolderB, after), folderSwappedForALinkBeforeItIsListed(Symlinks.NoLinks))
    }

    /**
     * What a walk in [symlinks] mode yields, of the empty folder `b` swapped for a link to its later sibling `c`, which
     * holds `y`, once the walk looked at `b`.
     */
    private fun folderSwappedForALinkToALaterSibling(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        val folder = dir / "b"
        createDirectoriesRaw(folder)
        createDirectoriesRaw(dir / "c")
        (dir / "c" / "y").writeRaw("y")
        lookups.after(Lookup.OwnInfo, folder) { folder.becomeLink(Path("c")) }
        return walk(symlinks)
    }

    /** The empty folder `b` then `c`, or `c` then `b` as a link to it. */
    private fun bAsFolderOrLinkToC(): List<List<Result<WalkEntry, WalkFailure>>> {
        val c = listOf(
            Ok(WalkEntry.Folder(dir / "c", Path("c"))),
            Ok(walkedFile(dir / "c" / "y", Path("c/y"), size = 1, viaLink = false)),
        )
        return listOf(emptyFolderB + c, c + bSkippedAs(WalkSkip.AlreadyEntered(Path("c"))))
    }

    @Test
    fun `under Inside a folder swapped for a link to a later sibling once looked at is the folder or that link`() {
        val walked = folderSwappedForALinkToALaterSibling(Symlinks.Inside)

        assertOneOf(bAsFolderOrLinkToC(), walked)
    }

    @Test
    fun `under Anywhere a folder swapped for a link to a later sibling once looked at is the folder or that link`() {
        val walked = folderSwappedForALinkToALaterSibling(Symlinks.Anywhere)

        assertOneOf(bAsFolderOrLinkToC(), walked)
    }

    @Test
    fun `under Anywhere a folder resolved through a link and a link again before its listing is walked there`() {
        (outside / "z").writeRaw("z")
        val folder = dir / "b"
        createDirectoriesRaw(folder)
        // Resolved through the link, looked at again as the folder, then the link for good.
        lookups.after(Lookup.OwnInfo, folder) { folder.becomeLink(outside) }
        lookups.after(Lookup.OneName, folder) { folder.becomeEmptyFolder() }
        lookups.after(Lookup.IsLink, folder) { folder.becomeLink(outside) }

        val walked = walk(Symlinks.Anywhere)

        val expected = listOf(
            WalkEntry.Folder(folder, Path("b")),
            walkedFile(folder / "z", Path("b/z"), size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }

    @Test
    fun `under Inside a link retargeted once the walk read what it leads to pairs no file with another's path`() {
        (outside / "t").writeRaw("abc")
        (dir / "u").writeRaw("x")
        val link = (dir / "a").linkTo(outside / "t")
        var lookedAt = false
        var retargeted = false
        val through = object : WalkLookups by lookups {
            override fun ownInfo(path: Path) = lookups.ownInfo(path).also { if (path == link) lookedAt = true }

            override fun info(path: Path) = lookups.info(path).also {
                if (lookedAt && !retargeted) {
                    retargeted = true
                    link.retarget(Path("u"))
                }
            }
        }

        val walked = at(link, walk(Symlinks.Inside, through = through))

        val allowed = listOf(
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.LeadsOutside))),
            listOf(Ok(walkedFile(link, Path("a"), size = 1, viaLink = true))),
        )
        assertOneOf(allowed, walked)
    }

    @Test
    fun `under Inside a link retargeted outside once the walk resolved it pairs no file with another's path`() {
        (dir / "t").writeRaw("abc")
        (outside / "u").writeRaw("x")
        val link = (dir / "a").linkTo(Path("t"))
        lookups.after(Lookup.OneName, link) { link.retarget(outside / "u") }

        // The link leads elsewhere by now, so its file's time is left out.
        val walked = at(link, walk(Symlinks.Inside)).timeless()

        val allowed = listOf(
            fileA(size = 3, viaLink = true),
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.LeadsOutside))),
        )
        assertOneOf(allowed, walked)
    }

    /**
     * What a walk in [symlinks] mode yields at the empty folder `b`, swapped for a link to the excluded folder `g` once
     * the walk looked at it.
     */
    private fun folderSwappedForALinkToAnExcludedFolder(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        val folder = dir / "b"
        createDirectoriesRaw(folder)
        val excluded = dir / "g"
        createDirectoriesRaw(excluded)
        lookups.after(Lookup.OwnInfo, folder) { folder.becomeLink(Path("g")) }
        return at(folder, walk(symlinks, exclude = setOf(excluded)))
    }

    @Test
    fun `under AsLinks a folder swapped for a link to an excluded folder once looked at is the folder or the link`() {
        val asLink = listOf(Ok(WalkEntry.Link(dir / "b", Path("b"), Path("g"))))
        assertOneOf(listOf(emptyFolderB, asLink), folderSwappedForALinkToAnExcludedFolder(Symlinks.AsLinks))
    }

    @Test
    fun `under NoLinks a folder swapped for a link to an excluded folder once looked at is the folder or the link`() {
        val asLink = bSkippedAs(WalkSkip.Link)
        assertOneOf(listOf(emptyFolderB, asLink), folderSwappedForALinkToAnExcludedFolder(Symlinks.NoLinks))
    }

    /**
     * What an Inside walk yields at `a`, a link to the folder `d` holding `y`, which `enter` refuses under its own
     * path, retargeted to a folder outside the root holding `z` once the walk resolved `a` [resolvedTimes] times.
     */
    private fun linkedFolderRetargetedOutside(resolvedTimes: Int): List<Result<WalkEntry, WalkFailure>> {
        createDirectoriesRaw(dir / "d")
        (dir / "d" / "y").writeRaw("y")
        (outside / "z").writeRaw("z")
        val link = (dir / "a").linkTo(Path("d"))
        var resolved = 0
        val through = object : WalkLookups by lookups {
            override fun oneName(path: Path) = lookups.oneName(path).also {
                if (path == link && ++resolved == resolvedTimes) link.retarget(outside)
            }
        }
        val walk = dir.walkTopDownWith(through, Symlinks.Inside, enter = { it.relative != Path("d") })
        // The link leads elsewhere by now, so each file's time is left out.
        return at(link, walk.assertSuccess().toList()).timeless()
    }

    private fun linkedFolderAnswers() = listOf(
        listOf(
            Ok(WalkEntry.Folder(dir / "a", Path("a"))),
            Ok(WalkEntry.File(dir / "a" / "y", Path("a/y"), 1, Instant.DISTANT_PAST, viaLink = false)),
        ),
        listOf(Ok(WalkEntry.Skipped(dir / "a", Path("a"), WalkSkip.LeadsOutside))),
    )

    @Test
    fun `under Inside a linked folder retargeted outside once judged at its turn is never listed there`() {
        assertOneOf(linkedFolderAnswers(), linkedFolderRetargetedOutside(resolvedTimes = 2))
    }

    @Test
    fun `under Inside a linked folder retargeted outside once asked again before its listing is never listed there`() {
        assertOneOf(linkedFolderAnswers(), linkedFolderRetargetedOutside(resolvedTimes = 3))
    }

    @Test
    fun `under Inside a folder below a linked folder retargeted outside before its listing is never listed there`() {
        createDirectoriesRaw(dir / "d" / "s")
        (dir / "d" / "s" / "w").writeRaw("w")
        createDirectoriesRaw(outside / "s")
        (outside / "s" / "z").writeRaw("z")
        val link = (dir / "a").linkTo(Path("d"))
        lookups.after(Lookup.OneName, link / "s") { link.retarget(outside) }

        val walk = dir.walkTopDownWith(lookups, Symlinks.Inside, enter = { it.relative != Path("d") })
        val walked = at(link / "s", walk.assertSuccess().toList())

        val allowed = listOf(
            listOf(
                Ok(WalkEntry.Folder(link / "s", Path("a/s"))),
                // The folder is reached through the retargeted link by now, so its file's time is left out.
                Ok(WalkEntry.File(link / "s" / "w", Path("a/s/w"), 1, Instant.DISTANT_PAST, viaLink = false)),
            ),
            listOf(Ok(WalkEntry.Skipped(link / "s", Path("a/s"), WalkSkip.LeadsOutside))),
        )
        assertOneOf(allowed, walked.timeless())
    }

    @Test
    fun `under Inside a dangling link swapped for a file once followed is the dangling link or the file`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.after(Lookup.Info, link) { link.becomeFile("xy") }

        val walked = at(link, walk(Symlinks.Inside))

        val allowed = listOf(
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.DanglingLink))),
            listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))),
        )
        assertOneOf(allowed, walked)
    }

    @Test
    fun `under Inside a link that goes round swapped for a file once followed is a loop or the file`() {
        val link = (dir / "a").linkTo(Path("a"))
        lookups.after(Lookup.Info, link) { link.becomeFile("xy") }

        val walked = at(link, walk(Symlinks.Inside))

        val allowed = listOf(
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), WalkSkip.LinkLoop))),
            listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))),
        )
        assertOneOf(allowed, walked)
    }

    @Test
    fun `under Inside a link that goes round removed once the walk resolved it is a loop or Gone`() {
        val link = (dir / "a").linkTo(Path("a"))
        lookups.after(Lookup.OneName, link) { deleteLink(link)?.let { throw it } }

        val walked = at(link, walk(Symlinks.Inside))

        val allowed = listOf(WalkSkip.LinkLoop, WalkSkip.Gone).map { why ->
            listOf(Ok(WalkEntry.Skipped(link, Path("a"), why)))
        }
        assertOneOf(allowed, walked)
    }

    /** Asserts a walk in [symlinks] mode never lists the folder `b`, swapped for a link to `g` once looked at. */
    private fun assertFolderSwappedForALinkIsNeverListed(symlinks: Symlinks) {
        val folder = dir / "b"
        createDirectoriesRaw(folder)
        createDirectoriesRaw(dir / "g")
        lookups.after(Lookup.OwnInfo, folder) { folder.becomeLink(Path("g")) }

        walk(symlinks)

        assertTrue(Lookup.List !in lookups.of(folder), "listed through the link: ${lookups.of(folder)}")
    }

    @Test
    fun `under AsLinks a folder swapped for a link once looked at is never listed through it`() {
        assertFolderSwappedForALinkIsNeverListed(Symlinks.AsLinks)
    }

    @Test
    fun `under NoLinks a folder swapped for a link once looked at is never listed through it`() {
        assertFolderSwappedForALinkIsNeverListed(Symlinks.NoLinks)
    }

    /** These steps with each file's time left out, for a test whose file is gone by the time it asserts. */
    private fun List<Result<WalkEntry, WalkFailure>>.timeless() = map { step ->
        step.map { if (it is WalkEntry.File) it.copy(modifiedAt = Instant.DISTANT_PAST) else it }
    }

    private fun fileA(
        size: Long,
        viaLink: Boolean,
    ) = listOf(Ok(WalkEntry.File(dir / "a", Path("a"), size, modifiedAt = Instant.DISTANT_PAST, viaLink)))

    /** What a walk in [symlinks] mode yields at the file `a`, swapped for a link to the file `t` once looked at. */
    private fun fileSwappedForALinkOnceLookedAt(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        val target = (dir / "t").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        lookups.afterFirst(file) { file.becomeLink(target) }
        return at(file, walk(symlinks)).timeless()
    }

    @Test
    fun `under Inside a file swapped for a link once looked at is the file or the link's file`() {
        val walked = fileSwappedForALinkOnceLookedAt(Symlinks.Inside)

        assertOneOf(listOf(fileA(size = 1, viaLink = false), fileA(size = 3, viaLink = true)), walked)
    }

    @Test
    fun `under Anywhere a file swapped for a link once looked at is the file or the link's file`() {
        val walked = fileSwappedForALinkOnceLookedAt(Symlinks.Anywhere)

        assertOneOf(listOf(fileA(size = 1, viaLink = false), fileA(size = 3, viaLink = true)), walked)
    }

    /** What a walk in [symlinks] mode yields at `a`, a link to the file `t` swapped for a file once looked at. */
    private fun linkSwappedForAFileOnceLookedAt(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        (dir / "t").writeRaw("abc")
        val link = (dir / "a").linkTo(Path("t"))
        lookups.afterFirst(link) { link.becomeFile("xy") }
        return at(link, walk(symlinks)).timeless()
    }

    @Test
    fun `under Inside a link to a file swapped for a file once looked at is the link's file or the file`() {
        val walked = linkSwappedForAFileOnceLookedAt(Symlinks.Inside)

        assertOneOf(listOf(fileA(size = 3, viaLink = true), fileA(size = 2, viaLink = false)), walked)
    }

    @Test
    fun `under Anywhere a link to a file swapped for a file once looked at is the link's file or the file`() {
        val walked = linkSwappedForAFileOnceLookedAt(Symlinks.Anywhere)

        assertOneOf(listOf(fileA(size = 3, viaLink = true), fileA(size = 2, viaLink = false)), walked)
    }

    /**
     * What a walk in [symlinks] mode yields at `a`, a file named as the excluded `c/a`, swapped for a link to the file
     * `t` once the walk resolved it, and swapped back for a file just after the walk saw that link by its own look.
     */
    private fun namesakeSwappedForALinkAndBack(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        createDirectoriesRaw(dir / "c")
        val excluded = (dir / "c" / "a").writeRaw("abcd")
        val target = (dir / "t").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        lookups.after(Lookup.OneName, file) { file.becomeLink(target) }
        var swappedBack = false
        val through = object : WalkLookups by lookups {
            override fun ownInfo(path: Path) = lookups.ownInfo(path).also { own ->
                if (path == file && !swappedBack && own.assertSuccess().type == FileType.Symlink) {
                    swappedBack = true
                    file.becomeFile("xy")
                }
            }
        }
        return at(file, walk(symlinks, exclude = setOf(excluded), through = through)).timeless()
    }

    @Test
    fun `under Inside a namesake swapped for a link and back is a file as it was or the link's file`() {
        val walked = namesakeSwappedForALinkAndBack(Symlinks.Inside)

        val allowed = listOf(1L to false, 3L to true, 2L to false).map { (size, viaLink) -> fileA(size, viaLink) }
        assertOneOf(allowed, walked)
    }

    @Test
    fun `under Anywhere a namesake swapped for a link and back is a file as it was or the link's file`() {
        val walked = namesakeSwappedForALinkAndBack(Symlinks.Anywhere)

        val allowed = listOf(1L to false, 3L to true, 2L to false).map { (size, viaLink) -> fileA(size, viaLink) }
        assertOneOf(allowed, walked)
    }

    /**
     * The file system's lookups with each real path spelled so it is not its folder's spelling joined with its name, as
     * on Windows, where a real path has backslashes and a join puts `/`.
     */
    private val respelling = object : WalkLookups by SystemLookups {
        override fun oneName(path: Path) = SystemLookups.oneName(path).respelled()

        override fun realPath(path: Path) = SystemLookups.realPath(path).map { it.respelled() }

        private fun Path.respelled() = parent?.let { Path(it.toString().uppercase(), name) } ?: this
    }

    /** What the walk yields at `sub/x`, excluded, in [symlinks] mode, with each real path [respelling] spells. */
    private fun excludedFileInASubfolderRespelled(symlinks: Symlinks): List<Result<WalkEntry, WalkFailure>> {
        createDirectoriesRaw(dir / "sub")
        val excluded = (dir / "sub" / "x").writeRaw("x")
        (dir / "sub" / "y").writeRaw("y")

        return at(excluded, walk(symlinks, exclude = setOf(excluded), through = respelling))
    }

    private val excludedInSub = listOf(Ok(WalkEntry.Skipped(dir / "sub" / "x", Path("sub/x"), WalkSkip.Excluded)))

    @Test
    fun `under Inside an excluded file is found by its real path as the lookups spell it`() {
        assertEquals(excludedInSub, excludedFileInASubfolderRespelled(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere an excluded file is found by its real path as the lookups spell it`() {
        assertEquals(excludedInSub, excludedFileInASubfolderRespelled(Symlinks.Anywhere))
    }

    @Test
    fun `under AsLinks an excluded file is found by its real path as the lookups spell it`() {
        assertEquals(excludedInSub, excludedFileInASubfolderRespelled(Symlinks.AsLinks))
    }

    @Test
    fun `under NoLinks an excluded file is found by its real path as the lookups spell it`() {
        assertEquals(excludedInSub, excludedFileInASubfolderRespelled(Symlinks.NoLinks))
    }

    @Test
    fun `under AsLinks a link whose target cannot be read is a WalkFailure for the reason the read gave`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.failLinkTarget = OsCallFailure(IoCause.Denied, link, "denied")

        val failure = assertIs<Err<WalkFailure>>(walk(Symlinks.AsLinks).single()).error

        assertEquals(link, failure.path)
        assertEquals(IoCause.Denied, assertIs<FileError.Inaccessible>(failure.error).cause)
    }

    @Test
    fun `under AsLinks a file swapped for a link to a folder once the walk looked at it is never entered`() {
        createDirectoriesRaw(dir / "elsewhere")
        val file = (dir / "a").writeRaw("x")
        val before = walkedFile(file, Path("a"), size = 1, viaLink = false)
        lookups.afterFirst(file) { file.becomeLink(dir / "elsewhere") }

        val walked = walk(Symlinks.AsLinks).filter { it.assertSuccess().path.parent == dir }

        val elsewhere = Ok(WalkEntry.Folder(dir / "elsewhere", Path("elsewhere")))
        assertEquals(elsewhere, walked.last())
        val asLink = WalkEntry.Link(file, Path("a"), dir / "elsewhere")
        assertOneOf(listOf(Ok(before), Ok(asLink)), walked.single { it != elsewhere })
    }

    @Test
    fun `under AsLinks a file swapped for a link to a file once the walk looked at it is the file or the link`() {
        val target = (dir / "t").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        val before = walkedFile(file, Path("a"), size = 1, viaLink = false)
        lookups.afterFirst(file) { file.becomeLink(target) }

        val walked = walk(Symlinks.AsLinks).first()

        assertOneOf(listOf(Ok(before), Ok(WalkEntry.Link(file, Path("a"), target))), walked)
    }

    @Test
    fun `under NoLinks a link swapped for a file before the walk looks at it is yielded as that file`() {
        val link = (dir / "a").linkTo(Path("missing"))
        lookups.beforeFirst(link) { link.becomeFile("xy") }

        val walked = walk(Symlinks.NoLinks)

        assertEquals(listOf(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false))), walked)
    }

    @Test
    fun `under NoLinks a file swapped for a link once the walk looked at it is the file or a passed over link`() {
        val target = (dir / "t").writeRaw("abc")
        val file = (dir / "a").writeRaw("x")
        val before = walkedFile(file, Path("a"), size = 1, viaLink = false)
        lookups.afterFirst(file) { file.becomeLink(target) }

        val walked = walk(Symlinks.NoLinks).first()

        assertOneOf(listOf(Ok(before), Ok(WalkEntry.Skipped(file, Path("a"), WalkSkip.Link))), walked)
    }

    @Test
    fun `under Inside a link to a folder swapped for a folder once the walk saw it as a link is walked once as it`() {
        createDirectoriesRaw(dir / "b")
        (dir / "b" / "y").writeRaw("y")
        val link = (dir / "a").linkTo(Path("b"))
        lookups.after(Lookup.IsLink, link) { link.becomeFolderHolding("x") }

        val walked = walk(Symlinks.Inside)

        val a = listOf(
            WalkEntry.Folder(link, Path("a")),
            walkedFile(link / "x", Path("a/x"), size = 1, viaLink = false),
        )
        val b = listOf(
            WalkEntry.Folder(dir / "b", Path("b")),
            walkedFile(dir / "b" / "y", Path("b/y"), size = 1, viaLink = false),
        )
        assertOneOf(listOf((a + b).map { Ok(it) }, (b + a).map { Ok(it) }), walked)
    }

    @Test
    fun `under Inside a link to a folder swapped for a file before its turn is yielded as that file at its turn`() {
        createDirectoriesRaw(dir / "b")
        (dir / "b" / "y").writeRaw("y")
        val link = (dir / "a").linkTo(Path("b"))
        val walk = dir.walkTopDownWith(lookups, Symlinks.Inside).assertSuccess().iterator()
        assertEquals(Ok(WalkEntry.Folder(dir / "b", Path("b"))), walk.next())
        assertEquals(Ok(walkedFile(dir / "b" / "y", Path("b/y"), size = 1, viaLink = false)), walk.next())

        link.becomeFile("xy")
        val next = walk.next()

        assertEquals(Ok(walkedFile(link, Path("a"), size = 2, viaLink = false)), next)
        assertEquals(false, walk.hasNext())
    }

    @Test
    fun `a root that is a symlink to a file is one entry a link led to`() {
        (dir / "a.txt").writeRaw("abc")
        val link = (dir / "b.txt").linkTo(Path("a.txt"))

        val expected = walkedFile(link, Path("b.txt"), size = 3, viaLink = true)
        assertEquals(listOf(Ok(expected)), link.walkTopDown(Symlinks.AsLinks).assertSuccess().toList())
    }

    /**
     * The lookups of the root `.` and of `real/x`, its namesake `x`, `file -> real/x` and `folder -> real`, walked in
     * [symlinks] mode, with the folder `skip` beside them and the file `real/x` excluded when [excluding].
     */
    private fun lookupsOfEach(
        symlinks: Symlinks,
        excluding: Boolean = false,
    ): Map<String, List<Lookup>> {
        createDirectoriesRaw(dir / "real")
        (dir / "real" / "x").writeRaw("x")
        (dir / "x").writeRaw("x")
        (dir / "file").linkTo(Path("real/x"))
        (dir / "folder").linkTo(Path("real"))
        createDirectoriesRaw(dir / "skip")

        walk(symlinks, exclude = if (excluding) setOf(dir / "skip", dir / "real" / "x") else emptySet())

        val entries = listOf("real", "real/x", "x", "file", "folder").associateWith { lookups.of(dir / it) }
        return mapOf("." to lookups.of(dir)) + entries
    }

    /** A folder no link is asked whether it is one when resolved, and before and after its listing. */
    private val folderAskedAgain =
        listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.IsLink, Lookup.IsLink, Lookup.List, Lookup.IsLink)

    @Test
    fun `under AsLinks each entry is looked at once and a link's target read once`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List),
            // Asked again before and after it is listed, whether it is still no link.
            "real" to listOf(Lookup.OwnInfo, Lookup.IsLink, Lookup.List, Lookup.IsLink),
            "real/x" to listOf(Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo),
            "file" to listOf(Lookup.OwnInfo, Lookup.LinkTarget),
            "folder" to listOf(Lookup.OwnInfo, Lookup.LinkTarget),
        )
        assertEquals(expected, lookupsOfEach(Symlinks.AsLinks))
    }

    @Test
    fun `under AsLinks with something excluded a file named as an excluded one is resolved and looked at again`() {
        val expected = mapOf(
            // Where each link is, against the links in exclude.
            "." to listOf(Lookup.Info, Lookup.List, Lookup.RealPath, Lookup.RealPath),
            "real" to folderAskedAgain,
            // Asked whether it is a link, which exclude names by its own place.
            "real/x" to listOf(Lookup.IsLink, Lookup.RealPath, Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "file" to listOf(Lookup.OwnInfo, Lookup.LinkTarget),
            "folder" to listOf(Lookup.OwnInfo, Lookup.LinkTarget),
        )
        assertEquals(expected, lookupsOfEach(Symlinks.AsLinks, excluding = true))
    }

    @Test
    fun `under NoLinks with something excluded a file named as an excluded one is resolved and looked at again`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List, Lookup.RealPath, Lookup.RealPath),
            "real" to folderAskedAgain,
            // Asked whether it is a link, which exclude names by its own place.
            "real/x" to listOf(Lookup.IsLink, Lookup.RealPath, Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "file" to listOf(Lookup.OwnInfo),
            "folder" to listOf(Lookup.OwnInfo),
        )
        assertEquals(expected, lookupsOfEach(Symlinks.NoLinks, excluding = true))
    }

    /** A link is looked at, resolved, and asked again once what is at its real path is read. */
    private val linkFollowed = listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.IsLink)

    /**
     * The folder `real`, read at its real path for `folder -> real` where the walk meets that link and at its turn, and
     * asked again around its listing.
     */
    private val realFolderAndItsLink = listOf(Lookup.Info) + folderAskedAgain + Lookup.Info

    @Test
    fun `under Inside with something excluded an excluded file is resolved once and a namesake looked at again`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List, Lookup.OneName),
            "real" to realFolderAndItsLink,
            // Read at its real path for `file`.
            "real/x" to listOf(Lookup.RealPath, Lookup.Info, Lookup.OwnInfo, Lookup.OneName),
            "x" to listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "file" to linkFollowed,
            "folder" to linkFollowed + linkFollowed,
        )
        assertEquals(expected, lookupsOfEach(Symlinks.Inside, excluding = true))
    }

    @Test
    fun `under Anywhere with something excluded an excluded file is resolved once and a namesake looked at again`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List, Lookup.OneName),
            "real" to realFolderAndItsLink,
            "real/x" to listOf(Lookup.RealPath, Lookup.Info, Lookup.OwnInfo, Lookup.OneName),
            "x" to listOf(Lookup.OwnInfo, Lookup.OneName, Lookup.OwnInfo),
            "file" to linkFollowed,
            "folder" to linkFollowed + linkFollowed,
        )
        assertEquals(expected, lookupsOfEach(Symlinks.Anywhere, excluding = true))
    }

    @Test
    fun `under NoLinks each entry is looked at once`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List),
            "real" to listOf(Lookup.OwnInfo, Lookup.IsLink, Lookup.List, Lookup.IsLink),
            "real/x" to listOf(Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo),
            "file" to listOf(Lookup.OwnInfo),
            "folder" to listOf(Lookup.OwnInfo),
        )
        assertEquals(expected, lookupsOfEach(Symlinks.NoLinks))
    }

    @Test
    fun `under Inside each entry is looked at itself once and a link resolved and asked again once followed`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List, Lookup.OneName),
            "real" to realFolderAndItsLink,
            "real/x" to listOf(Lookup.Info, Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo),
            "file" to linkFollowed,
            "folder" to linkFollowed + linkFollowed,
        )
        assertEquals(expected, lookupsOfEach(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere each entry is looked at itself once and a link resolved and asked again once followed`() {
        val expected = mapOf(
            "." to listOf(Lookup.Info, Lookup.List, Lookup.OneName),
            "real" to realFolderAndItsLink,
            "real/x" to listOf(Lookup.Info, Lookup.OwnInfo),
            "x" to listOf(Lookup.OwnInfo),
            "file" to linkFollowed,
            "folder" to linkFollowed + linkFollowed,
        )
        assertEquals(expected, lookupsOfEach(Symlinks.Anywhere))
    }
}

/** A lookup [RecordingLookups] records. */
internal enum class Lookup { IsLink, Info, OwnInfo, LinkTarget, OneName, RealPath, List }

/**
 * The file system's own lookups, each recorded with its path, with a change made once before or after the first
 * lookup of a path, or of one kind at a path, and the link target read failing with [failLinkTarget] when set.
 */
internal class RecordingLookups : WalkLookups {
    val made = mutableListOf<Pair<Lookup, Path>>()
    var failLinkTarget: Exception? = null
    private val before = mutableMapOf<Pair<Lookup?, Path>, () -> Unit>()
    private val after = mutableMapOf<Pair<Lookup?, Path>, () -> Unit>()

    fun beforeFirst(
        path: Path,
        change: () -> Unit,
    ) {
        before[null to path] = change
    }

    fun afterFirst(
        path: Path,
        change: () -> Unit,
    ) {
        after[null to path] = change
    }

    fun before(
        lookup: Lookup,
        path: Path,
        change: () -> Unit,
    ) {
        before[lookup to path] = change
    }

    fun after(
        lookup: Lookup,
        path: Path,
        change: () -> Unit,
    ) {
        after[lookup to path] = change
    }

    /** The lookups before or after which a change was set up but never made. */
    fun changesNotMade(): List<Pair<Lookup?, Path>> = (before.keys + after.keys).toList()

    /** The lookups made of [path], in order. */
    fun of(path: Path): List<Lookup> = made.filter { it.second == path }.map { it.first }

    private fun <T> record(
        lookup: Lookup,
        path: Path,
        look: () -> T,
    ): T {
        made += lookup to path
        (before.remove(null to path) ?: before.remove(lookup to path))?.invoke()
        return look().also { (after.remove(null to path) ?: after.remove(lookup to path))?.invoke() }
    }

    override fun isLink(path: Path): Boolean = record(Lookup.IsLink, path) { SystemLookups.isLink(path) }

    override fun info(path: Path): Result<FileInfo, FileError.LookupError> =
        record(Lookup.Info, path) { SystemLookups.info(path) }

    override fun ownInfo(path: Path): Result<FileInfo, FileError.LookupError> =
        record(Lookup.OwnInfo, path) { SystemLookups.ownInfo(path) }

    override fun linkTarget(path: Path): Path = record(Lookup.LinkTarget, path) {
        failLinkTarget?.let { throw it }
        SystemLookups.linkTarget(path)
    }

    override fun oneName(path: Path): Path = record(Lookup.OneName, path) { SystemLookups.oneName(path) }

    override fun realPath(path: Path): Result<Path, FileError.LookupError> =
        record(Lookup.RealPath, path) { SystemLookups.realPath(path) }

    override fun list(path: Path): Result<List<Path>, FileError> =
        record(Lookup.List, path) { SystemLookups.list(path) }
}
