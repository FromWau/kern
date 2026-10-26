package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.io.files.Path

class SingleInstanceKeyTest {
    private val dir = newTempDir()
    private val app: AppDirs = BaseDirs.under(dir / "home").forApp("app").assertSuccess()
    private val links = TestLinks()
    private val out = dir / "out"

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** A second instance for [key] while [first] holds one: LockBusy naming this process. */
    private fun assertBusy(
        first: InstanceLock,
        key: Path,
    ) {
        assertEquals(
            Err(FileError.LockBusy(first.lockFile, currentProcessId(), Duration.ZERO)),
            app.singleInstance(key),
        )
    }

    @Test
    fun `a key spelled with dot dot and a trailing slash is the same instance as the folder`() {
        createDirectoriesRaw(out)
        createDirectoriesRaw(dir / "src")

        app.singleInstance(out).assertSuccess().use { assertBusy(it, Path("$dir/src/../out/")) }
    }

    @Test
    fun `a key that is a link to the folder is the same instance as the folder`() {
        createDirectoriesRaw(out)
        val link = links.make(dir / "link", out)

        app.singleInstance(out).assertSuccess().use { assertBusy(it, link) }
    }

    @Test
    fun `a key not made yet takes an instance and leaves its lock and pid file in state`() {
        val held = app.singleInstance(dir / "later" / "out").assertSuccess()

        held.use {
            assertEquals(app.state, it.lockFile.parent)
            assertEquals(app.state, it.pidFile.parent)
            assertEquals("${currentProcessId()}\n", it.pidFile.readRaw())
            assertTrue(it.lockFile.name.startsWith(".out-"))
        }
    }

    @Test
    fun `two keys with one last name are two instances`() {
        createDirectoriesRaw(dir / "a" / "out")
        createDirectoriesRaw(dir / "b" / "out")

        app.singleInstance(dir / "a" / "out").assertSuccess().use {
            app.singleInstance(dir / "b" / "out").assertSuccess().close()
        }
    }

    @Test
    fun `an instance name is the last name and the FNV-1a hash of the resolved path`() {
        assertEquals("out-4c2894b7bcda536c", instanceNameOf(Path("/srv/site/out")))
    }

    @Test
    fun `an instance name of a last name too long for a lock file is cut to fit`() {
        val name = instanceNameOf(Path("/srv/" + "é".repeat(127)))

        assertNull(name.nameProblem())
        assertEquals(LONGEST_NAME_BYTES, ".$name.lock".encodeToByteArray().size)
        assertTrue(name.startsWith("é".repeat(116) + "-"))
    }

    @Test
    fun `an instance name never cuts a character in two`() {
        val name = instanceNameOf(Path("/srv/a" + "é".repeat(127)))

        assertTrue(name.startsWith("a" + "é".repeat(115) + "-"))
    }

    @Test
    fun `a key whose last name is long takes an instance`() {
        app.singleInstance(dir / "a".repeat(250)).assertSuccess().use {
            assertTrue(it.lockFile.name.encodeToByteArray().size <= LONGEST_NAME_BYTES)
        }
    }
}
