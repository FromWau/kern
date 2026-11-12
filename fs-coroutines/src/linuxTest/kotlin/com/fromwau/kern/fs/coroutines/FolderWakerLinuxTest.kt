package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.WriteMode
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
    fun `a change to the folder's own mode wakes the watch to look`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                dir.chmod(0b111_000_000).assertSuccess()
                assertEquals(Wake.Look, withTimeout(10.seconds) { it.next() })
            }
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
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.next() })
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
                assertNotEquals(Wake.Changed, withTimeout(10.seconds) { it.next() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `an entry made in a watched folder wakes the watch as a change to it`() = runBlocking {
        val dir = scratchDir()
        try {
            val sub = (dir / "sub").also { it.createDirectories().assertSuccess() }
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("sub"))))
            waker.use {
                (sub / "entry").createDirectories().assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `waits on more than 64 watches leave Dispatchers IO free`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                val waits = List(70) { _ -> launch(Dispatchers.Default) { while (true) { val _ = it.next() } } }
                delay(100.milliseconds)
                val took = measureTime { withContext(Dispatchers.IO) {} }
                waits.forEach { wait -> wait.cancelAndJoin() }
                assertTrue(took < 50.milliseconds, "a task on Dispatchers.IO waited $took")
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a write through a hard link in another folder wakes the watch as a change`() = runBlocking {
        val dir = scratchDir()
        try {
            val file = dir / "app.toml"
            file.writeText("a = 1").assertSuccess()
            val other = dir / "elsewhere" / "app-link.toml"
            other.hardLinkTo(file).assertSuccess()
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                other.writeText("a = 10", mode = WriteMode.InPlace).assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a file replaced by rename is watched through its hard links from then on`() = runBlocking {
        val dir = scratchDir()
        try {
            val file = dir / "app.toml"
            file.writeText("a = 1").assertSuccess()
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                file.writeText("a = 2").assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
                val other = dir / "elsewhere" / "app-link.toml"
                other.hardLinkTo(file).assertSuccess()
                while (withTimeoutOrNull(300.milliseconds) { it.nextNews() } != null) {
                    // the link's own events
                }

                other.writeText("a = 20", mode = WriteMode.InPlace).assertSuccess()
                assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    private suspend fun FolderWaker.nextNews(): Wake {
        while (true) {
            val wake = next()
            if (wake != Wake.Quiet) return wake
        }
    }
}
