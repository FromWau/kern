package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.readlink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class InotifyInstancesLinuxTest {
    private val dir = scratchDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    /** The descriptors of this process that are inotify instances, each with how many watches it holds. */
    @OptIn(ExperimentalForeignApi::class)
    private fun inotifyWatches(): Map<String, Int> = SystemFileSystem
        .list(Path("/proc/self/fd"))
        .filter { fd ->
            memScoped {
                val target = allocArray<ByteVar>(256)
                val length = readlink(fd.toString(), target, 255u)
                length > 0 && target.toKString().take(length.toInt()) == "anon_inode:inotify"
            }
        }.associate { fd ->
            val info = SystemFileSystem.source(Path("/proc/self/fdinfo", fd.name)).buffered().use { it.readString() }
            fd.name to info.lines().count { it.startsWith("inotify wd:") }
        }

    @Test
    fun `a chain of 40 links over 41 folders is watched by one inotify instance`() = runBlocking {
        (dir / "dotfiles" / "app.toml").writeText("a = 1").assertSuccess()
        repeat(40) { index ->
            val next = if (index == 39) "../dotfiles/app.toml" else "../f${index + 1}/app.toml"
            (dir / "f$index").createDirectories().assertSuccess()
            (dir / "f$index" / "app.toml").createSymlink(Path(next)).assertSuccess()
        }
        val before = inotifyWatches().keys
        val watching = CompletableDeferred<Map<String, Int>>()

        val collector = launch(Dispatchers.Default) {
            (dir / "f0" / "app.toml").changes().collect { watching.complete(inotifyWatches() - before) }
        }
        val opened = withTimeout(10.seconds) { watching.await() }
        collector.cancelAndJoin()

        assertEquals(listOf(41), opened.values.toList())
    }

    @Test
    fun `a missing folder among several leaves no inotify instance open`() {
        val before = inotifyWatches().keys

        val watch = openFolderWaker(mapOf(dir to setOf("app.toml"), dir / "missing" to setOf("app.toml")))

        assertEquals(FolderWatch.Missing, watch)
        assertEquals(before, inotifyWatches().keys)
    }
}
