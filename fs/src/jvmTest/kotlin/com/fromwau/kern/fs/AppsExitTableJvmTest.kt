package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.IgnoredBecause
import com.fromwau.kern.dirs.IgnoredVariable
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.io.files.Path

class AppsExitTableJvmTest {
    private val tool = CliToolSnippetTest()
    private val sites = mutableListOf<SiteBuilderSnippetTest>()

    @AfterTest
    fun cleanUp() {
        tool.cleanUp()
        sites.forEach { it.cleanUp() }
    }

    /** A site builder in a folder of its own, for one build's exit code. */
    private fun site(): SiteBuilderSnippetTest = SiteBuilderSnippetTest().also { sites += it }

    /** The exit code docs/apps.md lists for each filled cell of the column at [column], 0 for the tool. */
    private fun documentedExitCodes(column: Int): Map<String, Int> = File("docs/apps.md")
        .readLines()
        .dropWhile { it != "| exit code | command-line tool | daemon | site builder |" }
        .drop(2)
        .takeWhile { it.startsWith("| ") }
        .map { row -> row.trim('|').split('|').map { it.trim() } }
        .filter { cells -> cells[column + 1].isNotEmpty() }
        .associate { cells -> cells[column + 1] to cells[0].toInt() }

    @Test
    fun `the exit table of the apps page lists the code the tool exits with for each failure`() {
        val path = Path("x")
        val info = FileInfo(FileType.Directory, 0, Instant.DISTANT_PAST, null, null, ownedByMe = false, links = null)
        val refused = FileError.Refused(path, info, RefusedBecause.OtherOwner, path)
        val claimed = FileError.RunTempClaimed(path, holderPid = null)
        val backup = Leftover.Backup(file = path, copy = Path(".x.$A_WRITE_ID.bak"), startedAt = null)
        val relativeState = IgnoredVariable("XDG_STATE_HOME", "state", IgnoredBecause.NotAbsolute)
        val needsFixing = Ok(BaseDirs.under(Path("/nowhere")).copy(ignored = listOf(relativeState)))
        val unresolved = tool.runTool(Err(DirsError.NotAbsolute("HOME", "home"))) {}
        assertEquals(unresolved, tool.runTool(needsFixing) {})
        assertEquals(tool.exitCode(refused), tool.exitCode(claimed))

        val expected = mapOf(
            "done" to tool.exitOfMain(),
            "any other `FileError`" to tool.exitCode(FileError.NotFound(path)),
            "folders not resolved, or a variable to fix" to unresolved,
            "`LockBusy`: another run holds the lock" to tool.exitCode(FileError.LockBusy(path, null, Duration.ZERO)),
            "`Refused` or `RunTempClaimed`: a file or folder others may change, or the run temp taken" to
                tool.exitCode(refused),
            "`WriteFailed` with `IoCause.NoSpace`" to
                tool.exitCode(FileError.WriteFailed(path, IoCause.NoSpace, exception = null)),
            "`PendingBackup`: the config was put back from a killed write; run again" to
                tool.exitCode(FileError.PendingBackup(path, backup)),
        )
        assertEquals(expected, documentedExitCodes(column = 0))
    }

    @Test
    fun `the exit table of the apps page lists the code the site builder exits with for each outcome`() {
        val unresolved = site().exitWithoutFolders()
        assertEquals(unresolved, site().exitWithOneArgument())

        val expected = mapOf(
            "built, with nothing but accepted entries in `unclean`" to site().exitOfABuild(),
            "any other `FileError`, or `unclean` holds more" to site().exitOfABuildWithALinkLeadingOut(),
            "folders not resolved, or not two arguments" to unresolved,
            "`LockBusy`: another build of `out` runs" to site().exitOfASecondBuild(),
        )
        assertEquals(expected, documentedExitCodes(column = 2))
    }
}
