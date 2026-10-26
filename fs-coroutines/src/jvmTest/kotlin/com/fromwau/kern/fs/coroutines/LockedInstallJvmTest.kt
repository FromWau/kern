package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Both installs run at once for real, so the first one's build blocks its thread until the second is in place.
class LockedInstallJvmTest {
    private val scratch = scratchDir()
    private val dirs = AppDirs(
        config = scratch / "config",
        data = scratch / "data",
        state = scratch / "state",
        cache = scratch / "cache",
        temp = scratch / "tmp" / "run",
    )

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively().assertSuccess()
    }

    /** One file whose content is handed out once [proceed] lets it, after saying it was asked for on [asked]. */
    private class HeldFiles(
        private val asked: CountDownLatch,
        private val proceed: CountDownLatch,
    ) : AbstractMap<String, ByteArray>() {
        var proceeded = false

        override val entries: Set<Map.Entry<String, ByteArray>>
            get() {
                asked.countDown()
                proceeded = proceed.await(10, TimeUnit.SECONDS)
                return mapOf("a" to "first".encodeToByteArray()).entries
            }
    }

    @Test
    fun `two versions of one package install at once`() = runBlocking {
        val building = CountDownLatch(1)
        val secondPlaced = CountDownLatch(1)
        val held = HeldFiles(building, secondPlaced)

        val first = async(Dispatchers.IO) { dirs.installLocked("tool", "1.0", held) }
        assertTrue(building.await(10, TimeUnit.SECONDS), "the first install never started its build")
        val second = dirs.installLocked("tool", "2.0", mapOf("a" to "second".encodeToByteArray()))
        secondPlaced.countDown()

        assertEquals(Ok(Unit), second)
        assertEquals(Ok(Unit), first.await())
        assertTrue(held.proceeded, "2.0 was placed only after the build of 1.0 ended")
        assertEquals(Ok("first"), (dirs.cache / "packages" / "tool" / "1.0" / "a").readText())
        assertEquals(Ok("second"), (dirs.cache / "packages" / "tool" / "2.0" / "a").readText())
    }
}
