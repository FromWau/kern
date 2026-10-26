package com.fromwau.kern.fs.coroutines

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DaemonExitTableJvmTest {
    private val daemons = mutableListOf<DaemonSnippetTest>()

    @AfterTest
    fun cleanUp() {
        daemons.forEach { it.cleanUp() }
    }

    /** A daemon in a folder of its own, for one run's exit code. */
    private fun daemon(): DaemonSnippetTest = DaemonSnippetTest().also { daemons += it }

    /** The exit code fs's docs/apps.md lists for each filled cell of the daemon's column. */
    private fun documentedDaemonExitCodes(): Map<String, Int> = File("../fs/docs/apps.md")
        .readLines()
        .dropWhile { it != "| exit code | command-line tool | daemon | site builder |" }
        .drop(2)
        .takeWhile { it.startsWith("| ") }
        .map { row -> row.trim('|').split('|').map { it.trim() } }
        .filter { cells -> cells[2].isNotEmpty() }
        .associate { cells -> cells[2] to cells[0].toInt() }

    @Test
    fun `the exit table of the apps page lists the code the daemon exits with for each outcome`() = runTest {
        val expected = mapOf(
            "done" to daemon().exitOfADaemon(),
            "any other `FileError`" to daemon().exitWithAFileAsState(),
            "folders not resolved" to daemon().exitWithoutFolders(),
            "`LockBusy`" to daemon().exitOfASecondDaemon(),
            "`LockLost`: lock file removed" to daemon().exitOfADaemonWhoseLockIsLost(),
        )

        assertEquals(expected, documentedDaemonExitCodes())
    }
}
