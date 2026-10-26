package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class FolderWakerLinuxTest {
    @Test
    fun `a folder is watched with inotify`() {
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
    fun `a change to the folder's own mode wakes the watch`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                dir.chmod(0b111_000_000).assertSuccess()
                assertEquals(Wake.Event, withTimeout(10.seconds) { it.next() })
            }
        } finally {
            SystemFileSystem.delete(dir)
        }
    }
}
