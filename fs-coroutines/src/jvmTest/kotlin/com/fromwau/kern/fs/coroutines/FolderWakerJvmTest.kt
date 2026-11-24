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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

class FolderWakerJvmTest {
    @Test
    fun `a folder is watched everywhere but macOS`() {
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
    fun `a folder made and removed while its watch opens is never Refused`() {
        if (System.getProperty("os.name").startsWith("Mac")) return
        val dir = scratchDir()
        val folder = File((dir / "config").toString())
        val stop = AtomicBoolean(false)
        val toggler = thread {
            while (!stop.get()) {
                val _ = folder.mkdir()
                val _ = folder.delete()
            }
        }
        try {
            repeat(2_000) { _ ->
                val watch = openFolderWaker(mapOf(dir / "config" to setOf("app.toml")))
                (watch as? FolderWaker)?.close()
                assertNotEquals(FolderWatch.Refused, watch)
            }
        } finally {
            stop.set(true)
            toggler.join()
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a change to the folder's own mode wakes the watch to look`() = runBlocking {
        val dir = scratchDir()
        try {
            val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker ?: return@runBlocking
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
            val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker ?: return@runBlocking
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
            val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker ?: return@runBlocking
            waker.use {
                (dir / "log.txt").writeText("started").assertSuccess()
                assertNotEquals(Wake.Changed, withTimeout(10.seconds) { it.next() })
            }
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `a write through a hard link in another folder wakes the watch as a change where inotify is reached by FFM`() =
        runBlocking {
            if (!inotifyByFfm) return@runBlocking
            val dir = scratchDir()
            try {
                val file = dir / "app.toml"
                file.writeText("a = 1").assertSuccess()
                val other = dir / "elsewhere" / "app-link.toml"
                other.hardLinkTo(file).assertSuccess()
                val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as FolderWaker
                waker.use {
                    other.writeText("a = 10", mode = WriteMode.InPlace).assertSuccess()
                    assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
                }
            } finally {
                dir.deleteRecursively().assertSuccess()
            }
        }

    @Test
    fun `an open for writing closed unchanged wakes the watch as a change where inotify is reached by FFM`() =
        runBlocking {
            if (!inotifyByFfm) return@runBlocking
            val dir = scratchDir()
            try {
                val file = dir / "app.toml"
                file.writeText("a = 1").assertSuccess()
                val waker = openFolderWaker(mapOf(dir to setOf("app.toml"))) as FolderWaker
                waker.use {
                    java.io.FileOutputStream(file.toString(), true).close()
                    assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
                }
            } finally {
                dir.deleteRecursively().assertSuccess()
            }
        }

    @Test
    fun `an entry made in a watched folder wakes the watch as a change to it where inotify is reached by FFM`() =
        runBlocking {
            if (!inotifyByFfm) return@runBlocking
            val dir = scratchDir()
            try {
                val sub = (dir / "sub").also { it.createDirectories().assertSuccess() }
                val waker = openFolderWaker(mapOf(dir to setOf("sub"))) as FolderWaker
                waker.use {
                    (sub / "entry").createDirectories().assertSuccess()
                    assertEquals(Wake.Changed, withTimeout(10.seconds) { it.nextNews() })
                }
            } finally {
                dir.deleteRecursively().assertSuccess()
            }
        }

    @Test
    fun `a JDK that denies native access watches through the WatchService`() {
        // --illegal-native-access is there from Java 24 on.
        if (!inotifyByFfm || Runtime.version().feature() < 24) return
        val dir = scratchDir()
        try {
            val java = ProcessHandle.current().info().command().get()
            val process = ProcessBuilder(
                java,
                "--illegal-native-access=deny",
                "-cp",
                System.getProperty("java.class.path"),
                OpenFolderWaker::class.java.name,
                dir.toString(),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), output)
            assertEquals("WatchServiceWaker", output.lines().last { it.isNotBlank() }, output)
        } finally {
            SystemFileSystem.delete(dir)
        }
    }

    // Linux on a JDK with FFM, Java 22 on; elsewhere the WatchService is all there is.
    private val inotifyByFfm = System.getProperty("os.name") == "Linux" && Runtime.version().feature() >= 22

    private suspend fun FolderWaker.nextNews(): Wake {
        while (true) {
            val wake = next()
            if (wake != Wake.Quiet) return wake
        }
    }
}

/** Opens a watch on the folder named by its one argument in a JVM of its own and prints the kind of watch it got. */
object OpenFolderWaker {
    @JvmStatic
    fun main(args: Array<String>) {
        val watch = openFolderWaker(mapOf(kotlinx.io.files.Path(args.single()) to setOf("app.toml")))
        println(watch::class.simpleName)
        (watch as? FolderWaker)?.close()
    }
}
