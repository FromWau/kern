package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.result
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The site builder from docs/apps.md, as written there, with the process's own calls stood in for. */
class SiteBuilderSnippetTest {
    private val dir = newTempDir()
    private val links = TestLinks()
    private val pagesFolder = (dir / "pages").also { createDirectoriesRaw(it) }
    private val outFolder = dir / "public"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private val errors = mutableListOf<String>()

    /** Stands in for kern:terminal's terminal, so a test can read what the builder writes to stderr. */
    private inner class ErrorTerminal {
        fun err(text: String) {
            errors += text
        }
    }

    private fun defaultTerminal() = ErrorTerminal()

    /** How [exitProcess] ends the builder here, with its [code]. */
    private class Exited(val code: Int) : Exception()

    /** Stands in for `kotlin.system.exitProcess`, which would end the test run. */
    private fun exitProcess(code: Int): Nothing = throw Exited(code)

    /** Stands in for the platform's `BaseDirs.current`, which reads this process's own environment. */
    private fun BaseDirs.Companion.current(): Result<BaseDirs, DirsError> = Ok(BaseDirs.under(dir))

    /** What rendering a page does besides writing it, set by each test. */
    private var alsoRender: () -> Unit = {}

    /** Stands in for your renderer: writes [page] into [into] as [name], here as a plain copy. */
    private fun render(
        page: Path,
        into: Writer,
        name: String,
    ): EmptyResult<FileError> {
        alsoRender()
        return into.copyFrom(page, name).map { }
    }

    /** The exit code `main` ended with for [args], by default [pagesFolder] and [outFolder]. */
    private fun exitOfMain(args: Array<String> = arrayOf("$pagesFolder", "$outFolder")): Int =
        assertFailsWith<Exited> { main(args) }.code

    /** The code a build of one page exits with, the first build of [outFolder]. */
    internal fun exitOfABuild(): Int {
        (pagesFolder / "index.html").writeRaw("i")
        return exitOfMain()
    }

    /** The code a build exits with whose pages hold a link leading out of them. */
    internal fun exitOfABuildWithALinkLeadingOut(): Int {
        (pagesFolder / "index.html").writeRaw("i")
        links.make(pagesFolder / "up", dir)
        return exitOfMain()
    }

    /** The code a second build of [outFolder] exits with while the first runs; the first must exit 0. */
    internal fun exitOfASecondBuild(): Int {
        (pagesFolder / "index.html").writeRaw("i")
        var second = -1
        alsoRender = {
            alsoRender = {}
            second = exitOfMain()
        }
        assertEquals(0, exitOfMain())
        return second
    }

    /** The code `main` exits with when given the pages folder alone. */
    internal fun exitWithOneArgument(): Int = exitOfMain(arrayOf("$pagesFolder"))

    /** The code the builder exits with on folders that could not be resolved. */
    internal fun exitWithoutFolders(): Int =
        buildSite(Err(DirsError.NotAbsolute("HOME", "home")), pagesFolder, outFolder) { errors += it }

    /** Builds the site from the pages in the first argument into the folder in the second, and exits with its code. */
    fun main(args: Array<String>) {
        if (args.size != 2) {
            defaultTerminal().err("usage: site <pages> <out>\n")
            exitProcess(2)
        }
        val code = buildSite(BaseDirs.current(), Path(args[0]), Path(args[1])) { defaultTerminal().err("site: $it\n") }
        exitProcess(code)
    }

    /** Builds [pages] into [out] once no other build of [out] runs, writing each problem through [err]. */
    fun buildSite(
        found: Result<BaseDirs, DirsError>,
        pages: Path,
        out: Path,
        err: (String) -> Unit,
    ): Int {
        val dirs = found.flatMap { it.forApp("site") }.getOrElse { failure ->
            err("no folders: $failure")
            return 2
        }
        val built: Result<MirrorReport, FileError> = result {
            dirs.singleInstance(out).bind().use {
                dirs.clearStaleTempRuns().onError { err("old runs not cleared: $it") }
                dirs.withRunTemp { temp ->
                    pages.mirrorTo(
                        out.writer().bind(),
                        isCurrent = { source, existing -> existing.modifiedAt == source.modifiedAt },
                        copy = { source, site, name ->
                            render(source.path, temp, name).flatMap {
                                site.copyFrom(temp.root / name, name, modifiedAt = source.modifiedAt)
                            }.map { }
                        },
                    ).bind()
                }.bind()
            }
        }
        val report = built.getOrElse { failure ->
            err("$failure")
            return if (failure is FileError.LockBusy) 3 else 1
        }
        val unclean = report.unclean.filterNot { reason ->
            reason is MirrorReport.Unclean.Kept && reason.removal.why is RemovalReason.AtPassedOver
        }
        unclean.forEach { err("not published: $it") }
        return if (unclean.isEmpty()) 0 else 1
    }

    @Test
    fun `the builder publishes the pages and removes what the site no longer has`() {
        (pagesFolder / "index.html").writeRaw("i")
        (pagesFolder / "old.html").writeRaw("o")
        assertEquals(0, exitOfMain())
        SystemFileSystem.delete(pagesFolder / "old.html")

        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
        assertEquals(mapOf("index.html" to "i"), outFolder.tree())
    }

    @Test
    fun `the builder publishes the pages in subfolders`() {
        createDirectoriesRaw(pagesFolder / "docs" / "guide")
        (pagesFolder / "docs" / "guide" / "start.html").writeRaw("s")

        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
        assertEquals(mapOf("docs" to "dir", "docs/guide" to "dir", "docs/guide/start.html" to "s"), outFolder.tree())
    }

    @Test
    fun `a pages folder that cannot be read keeps its old pages and the build exits 1`() {
        (pagesFolder / "index.html").writeRaw("i")
        val secret = (pagesFolder / "secret").also { createDirectoriesRaw(it) }
        (secret / "s.html").writeRaw("s")
        assertEquals(0, exitOfMain())

        secret.withMode(0, stillWorks = { secret.list() is Result.Success }) {
            assertEquals(1, exitOfMain())

            assertTrue(errors.single().startsWith("site: not published: Failed("), errors.toString())
            assertEquals(mapOf("index.html" to "i", "secret" to "dir", "secret/s.html" to "s"), outFolder.tree())
        }
    }

    @Test
    fun `the builder leaves a page that did not change alone`() {
        assertEquals(0, exitOfABuild())
        val page = outFolder / "index.html"
        val published = page.info().assertSuccess()
        // Same size and time, other text: only a page left alone still holds it after the next build.
        val out = outFolder.writer().assertSuccess()
        out.writeText("index.html", "x", modifiedAt = published.modifiedAt).assertSuccess()

        assertEquals(0, exitOfMain())

        assertEquals(emptyList(), errors)
        assertEquals("x", page.readRaw())
    }

    @Test
    fun `a second build of the same folder while the first runs exits 3`() {
        assertEquals(3, exitOfASecondBuild())

        assertTrue(errors.single().startsWith("site: LockBusy("), errors.toString())
    }

    @Test
    fun `a link leading out of the pages is not published and the build exits 1`() {
        assertEquals(1, exitOfABuildWithALinkLeadingOut())

        assertTrue(errors.single().startsWith("site: not published: LinkNotMade("), errors.toString())
        assertEquals(mapOf("index.html" to "i"), outFolder.tree())
    }

    @Test
    fun `the copy at the place of a FIFO the default keeps is accepted and the build exits 0`() {
        (pagesFolder / "feed").writeRaw("f")
        assertEquals(0, exitOfMain())
        SystemFileSystem.delete(pagesFolder / "feed")
        val fifo = (pagesFolder / "feed").mkfifo()

        val code = exitOfMain()

        // Windows has no FIFOs, which leaves nothing to observe.
        if (fifo !is Result.Success) return
        assertEquals(0, code)
        assertEquals(emptyList(), errors)
        assertEquals(mapOf("feed" to "f"), outFolder.tree())
    }

    @Test
    fun `the builder exits 2 on folders that could not be resolved`() {
        assertEquals(2, exitWithoutFolders())

        assertEquals(listOf("no folders: ${DirsError.NotAbsolute("HOME", "home")}"), errors)
    }

    @Test
    fun `main exits 2 with a usage line unless it is given two arguments and builds nothing`() {
        assertEquals(2, exitWithOneArgument())
        assertEquals(2, exitOfMain(arrayOf("$pagesFolder", "$outFolder", "extra")))

        assertEquals(listOf("usage: site <pages> <out>\n", "usage: site <pages> <out>\n"), errors)
        assertFalse(SystemFileSystem.exists(outFolder))
    }
}
