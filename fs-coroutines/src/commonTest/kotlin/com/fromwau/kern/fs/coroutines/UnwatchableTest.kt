package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.PseudoFileSystem
import com.fromwau.kern.fs.canonicalPath
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.exists
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// procfs and sysfs exist only on Linux, so where they are missing these pass without asking.
class UnwatchableTest {
    private val meminfo = Path("/proc/meminfo")
    private val sysfsFile = Path("/sys/kernel/uevent_seqnum")

    @Test
    fun `a system watch on a procfs file answers Unwatchable once and ends`() = runTest {
        if (!meminfo.exists()) return@runTest

        val answers = inRealTime { meminfo.changes(Watch.System()).toList() }

        assertEquals(listOf(Err(FileError.Unwatchable(meminfo, PseudoFileSystem.Proc))), answers)
    }

    @Test
    fun `a poll on a sysfs file answers Unwatchable once and ends`() = runTest {
        if (!sysfsFile.exists()) return@runTest

        val answers = inRealTime { sysfsFile.changes(Watch.Poll(100.milliseconds)).toList() }

        assertEquals(listOf(Err(FileError.Unwatchable(sysfsFile, PseudoFileSystem.Sys))), answers)
    }

    @Test
    fun `text under a system watch on a procfs file is Unwatchable and never read`() = runTest {
        if (!meminfo.exists()) return@runTest

        val answers = inRealTime { meminfo.textChanges(maxBytes = 8, watch = Watch.System()).toList() }

        assertEquals(listOf(Err(FileError.Unwatchable(meminfo, PseudoFileSystem.Proc))), answers)
    }

    @Test
    fun `a missing file in procfs is Unwatchable too as nothing there could be watched`() = runTest {
        val missing = Path("/proc/kern-no-such-file")
        if (!Path("/proc").exists()) return@runTest

        val answers = inRealTime { missing.changes(Watch.System()).toList() }

        assertEquals(listOf(Err(FileError.Unwatchable(missing, PseudoFileSystem.Proc))), answers)
    }

    @Test
    fun `a cgroup2 events file is watched since its kernel tells of each change`() = runTest {
        val cgroup = Path("/proc/self/cgroup").readText(4096).getOrNull()?.lineSequence()
            ?.firstOrNull { it.startsWith("0::") }
            ?.removePrefix("0::")
        val events = Path("/sys/fs/cgroup$cgroup/cgroup.events")
        if (cgroup == null || !events.exists()) return@runTest

        val first = inRealTime { FileWatcher(events).changes.first() }

        assertTrue(first is Result.Success, "first: $first")
    }

    @Test
    fun `a timed read on a procfs file reads it`() = runTest {
        if (!meminfo.exists()) return@runTest

        val texts = meminfo.textChanges(maxBytes = 1_048_576, watch = TextWatch.Read(1.seconds))
        val first = inRealTime { texts.first() }

        assertTrue(first is Result.Success && "MemTotal" in first.value, "first: $first")
    }

    @Test
    fun `a link to a procfs file is Unwatchable`() = runTest {
        if (!meminfo.exists()) return@runTest
        val dir = scratchDir()
        val link = dir / "meminfo"
        try {
            if (link.createSymlink(meminfo) !is Result.Success) return@runTest

            val answers = inRealTime { link.changes(Watch.System()).toList() }

            assertEquals(listOf(Err(FileError.Unwatchable(link, PseudoFileSystem.Proc))), answers)
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    @Test
    fun `an ordinary file reached through proc self root is watched`() = runTest {
        if (!Path("/proc/self/root").exists()) return@runTest
        val dir = scratchDir()
        val config = dir / "app.toml"
        try {
            config.writeText("a = 1").assertSuccess()
            val throughProc = Path("/proc/self/root${config.canonicalPath().assertSuccess()}")

            val texts = throughProc.textChanges(maxBytes = 64, watch = Watch.Poll(100.milliseconds))
            val first = inRealTime { texts.first() }

            assertEquals(Ok("a = 1"), first)
        } finally {
            dir.deleteRecursively().assertSuccess()
        }
    }

    private suspend fun <T> inRealTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { block() } }
}
