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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
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
    fun `a wait goes on while every Dispatchers IO thread is taken`() = runBlocking {
        val dir = scratchDir()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        // Each holds its thread until released, as blocking work on Dispatchers.IO does.
        val holders = List(IO_THREADS) { _ ->
            launch(Dispatchers.IO) {
                started.send(Unit)
                runBlocking { release.await() }
            }
        }
        try {
            withTimeout(10.seconds) { repeat(IO_THREADS) { _ -> started.receive() } }
            val waker = assertIs<FolderWaker>(openFolderWaker(mapOf(dir to setOf("app.toml"))))
            waker.use {
                // Not cancelled on a timeout: a wait that never got a thread could not finish its cancellation either.
                val wait = async { it.next() }
                val woke = withTimeoutOrNull(10.seconds) { wait.await() }
                release.complete(Unit)
                wait.join()
                val _ = assertNotNull(woke, "a wait got no thread while Dispatchers.IO was full")
            }
        } finally {
            release.complete(Unit)
            holders.joinAll()
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

    private companion object {
        // What kotlinx.coroutines gives Dispatchers.IO on Kotlin/Native.
        const val IO_THREADS = 64
    }
}
