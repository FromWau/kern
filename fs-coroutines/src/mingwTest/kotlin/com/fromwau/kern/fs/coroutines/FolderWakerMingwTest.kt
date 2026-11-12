package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class FolderWakerMingwTest {
    @Test
    fun `a folder is watched with ReadDirectoryChangesW`() {
        val dir = scratchDir()
        try {
            assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml")))).close()
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `a folder that is not there is Missing`() {
        val dir = scratchDir()
        try {
            assertEquals(FolderWatch.Missing, openFolderWaker(mapOf(dir / "missing" to setOf("app.toml"))))
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `a write to the watched file wakes the watch as a change to it`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                (dir / "app.toml").writeText("a = 1").assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a watched file is found whatever the case of its name`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("App.toml"))))
            waker.use {
                (dir / "app.toml").writeText("a = 1").assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a write to another file in the folder does not wake the watch as a change`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                (dir / "log.txt").writeText("started").assertSuccess()
                val wakes = mutableListOf<Wake>()
                val _ = withTimeoutOrNull(1.seconds) { while (true) wakes += it.next() }
                assertFalse(Wake.Changed in wakes, "wakes: $wakes")
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    // A batch may hold only the stage a write makes beside the file, before the rename that names it.
    private suspend fun FolderWaker.nextNews(): Wake {
        while (true) {
            val wake = next()
            if (wake != Wake.Quiet) return wake
        }
    }
}
