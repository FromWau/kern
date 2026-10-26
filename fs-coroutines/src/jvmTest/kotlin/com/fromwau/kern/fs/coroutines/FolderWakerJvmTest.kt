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
import kotlin.time.Duration.Companion.seconds

class FolderWakerJvmTest {
    @Test
    fun `a folder is watched with a WatchService everywhere but macOS`() {
        val dir = scratchDir()
        try {
            val watch = openFolderWaker(mapOf(dir to setOf("app.toml")))
            (watch as? FolderWaker)?.close()
            assertEquals(!System.getProperty("os.name").startsWith("Mac"), watch is FolderWaker)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `a folder that is not there is Missing everywhere but macOS`() {
        val dir = scratchDir()
        try {
            val onMac = System.getProperty("os.name").startsWith("Mac")
            val expected = if (onMac) FolderWatch.Refused else FolderWatch.Missing
            assertEquals(expected, openFolderWaker(mapOf(dir / "missing" to setOf("app.toml"))))
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `a change to the folder's own mode wakes the watch`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker ?: return@runBlocking
            waker.use {
                dir.chmod(0b111_000_000).assertSuccess()
                assertEquals(Wake.Event, withTimeout(10.seconds) { it.next() })
            }
        } finally {
            SystemFileSystem.delete(dir)
        }
    }
}
