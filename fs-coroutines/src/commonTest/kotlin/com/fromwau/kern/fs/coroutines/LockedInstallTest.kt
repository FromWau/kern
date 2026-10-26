package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createDirectoryAtomically
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.list
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.tryLock
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.fs.writer
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.orElse
import com.fromwau.kern.result.result
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LockedInstallTest {
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

    @Test
    fun `the locked install puts the whole version in place`() = runTest {
        dirs.installLocked("tool", "1.0", mapOf("bin/tool" to "a".encodeToByteArray())).assertSuccess()

        assertEquals(Ok("a"), (dirs.cache / "packages" / "tool" / "1.0" / "bin" / "tool").readText())
    }

    @Test
    fun `the locked install of a file outside the package answers InvalidRelativePath and places nothing`() = runTest {
        val files = mapOf("a" to "a".encodeToByteArray(), "../evil" to "b".encodeToByteArray())

        dirs.installLocked("tool", "1.0", files).assertError<FileError.InvalidRelativePath>()

        assertEquals(Ok(emptyList()), (dirs.cache / "packages" / "tool").list())
    }

    @Test
    fun `the locked install waits for another install of the version and answers LockBusy after its wait`() =
        runTest {
            (dirs.cache / "locks" / "tool").writer().assertSuccess()
            (dirs.cache / "locks" / "tool" / "1.0.lock").tryLock().assertSuccess().use {
                val busy = dirs
                    .installLocked("tool", "1.0", mapOf("a" to "a".encodeToByteArray()))
                    .assertError<FileError.LockBusy>()

                assertEquals(30.seconds, busy.waited)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a locked install that waited for another install of the version answers it installed`() = runTest {
        val locks = (dirs.cache / "locks").writer().assertSuccess()
        val first = locks.tryLock("tool/1.0.lock").assertSuccess()
        val waiter = async { dirs.installLocked("tool", "1.0", mapOf("a" to "second".encodeToByteArray())) }
        runCurrent()

        (dirs.cache / "packages").writer().assertSuccess()
            .createDirectoryAtomically("tool/1.0") { stage -> stage.writeText("a", "first").assertSuccess() }
            .assertSuccess()
        first.close()

        assertEquals(Ok(Unit), waiter.await())
        assertTrue(testScheduler.currentTime > 0, "the install did not wait")
        assertEquals(Ok("first"), (dirs.cache / "packages" / "tool" / "1.0" / "a").readText())
    }

    /** One file whose content is handed out once [meanwhile] ran, the build of a version going on until then. */
    private class FilesAfter(private val meanwhile: () -> Unit) : AbstractMap<String, ByteArray>() {
        override val entries: Set<Map.Entry<String, ByteArray>>
            get() {
                meanwhile()
                return mapOf("a" to "first".encodeToByteArray()).entries
            }
    }

    @Test
    fun `another version of a package installs while the build of the first is going on`() = runTest {
        var second: EmptyResult<FileError>? = null
        val held = FilesAfter {
            second = runBlocking { dirs.installLocked("tool", "2.0", mapOf("a" to "second".encodeToByteArray())) }
        }

        val first = dirs.installLocked("tool", "1.0", held)

        assertEquals(Ok(Unit), second)
        assertEquals(Ok(Unit), first)
        assertEquals(Ok("first"), (dirs.cache / "packages" / "tool" / "1.0" / "a").readText())
        assertEquals(Ok("second"), (dirs.cache / "packages" / "tool" / "2.0" / "a").readText())
    }

    @Test
    fun `a link at the place of the version is AlreadyExists for a Symlink and not answered installed`() = runTest {
        val elsewhere = (scratch / "elsewhere").also { it.createDirectories().assertSuccess() }
        val place = dirs.cache / "packages" / "tool" / "1.0"
        (dirs.cache / "packages" / "tool").createDirectories().assertSuccess()
        place.createSymlink(elsewhere).assertSuccess()

        val failure = dirs.installLocked("tool", "1.0", mapOf("a" to "a".encodeToByteArray()))

        assertEquals(FileType.Symlink, failure.assertError<FileError.AlreadyExists>().type)
    }

    @Test
    fun `a file at the place of the version is AlreadyExists for a Regular file and not answered installed`() =
        runTest {
            (dirs.cache / "packages" / "tool").createDirectories().assertSuccess()
            (dirs.cache / "packages" / "tool" / "1.0").writeText("not a package").assertSuccess()

            val failure = dirs.installLocked("tool", "1.0", mapOf("a" to "a".encodeToByteArray()))

            assertEquals(FileType.Regular, failure.assertError<FileError.AlreadyExists>().type)
        }

    @Test
    fun `a folder at the place of the version is answered installed`() = runTest {
        (dirs.cache / "packages" / "tool" / "1.0").createDirectories().assertSuccess()

        assertEquals(Ok(Unit), dirs.installLocked("tool", "1.0", mapOf("a" to "a".encodeToByteArray())))
    }

    @Test
    fun `the switch of the version in use points current at it`() = runTest {
        dirs.installLocked("tool", "1.0", mapOf("a" to "a".encodeToByteArray())).assertSuccess()

        dirs.useVersion("tool", "1.0").assertSuccess()

        assertEquals(Ok("a"), (dirs.cache / "packages" / "tool" / "current" / "a").readText())
    }

    @Test
    fun `the switch of the version in use waits for another switch and answers LockBusy after its wait`() =
        runTest {
            val locks = (dirs.cache / "locks").writer().assertSuccess()
            locks.tryLock("tool/current.lock").assertSuccess().use {
                assertEquals(30.seconds, dirs.useVersion("tool", "1.0").assertError<FileError.LockBusy>().waited)
            }
        }

    @Test
    fun `a package named like a lock file leaves the install of another package free`() = runTest {
        val one = mapOf("a" to "a".encodeToByteArray())
        dirs.installLocked(".bar.lock", "1.0", one).assertSuccess()
        dirs.installLocked("bar.lock", "1.0", one).assertSuccess()

        dirs.installLocked("bar", "1.0", one).assertSuccess()

        assertEquals(Ok("a"), (dirs.cache / "packages" / "bar" / "1.0" / "a").readText())
        val packages = (dirs.cache / "packages").writer().assertSuccess()
        assertEquals(setOf(".bar.lock", "bar", "bar.lock"), packages.list().assertSuccess().toSet())
    }
}

/** The locked install from fs's docs/folders-and-moves.md, as written there. */
internal suspend fun AppDirs.installLocked(
    name: String,
    version: String,
    files: Map<String, ByteArray>,
): EmptyResult<FileError> = result {
    val locks = (cache / "locks").writer().bind()
    val packages = (cache / "packages").writer().bind()
    locks.awaitLock("$name/$version.lock", wait = 30.seconds) {
        packages.createDirectoryAtomically("$name/$version") { stage ->
            for ((file, bytes) in files) stage.writeBytes(file, bytes).bind()
        }.orElse { failure ->
            val installed = failure is FileError.AlreadyExists && failure.type == FileType.Directory
            if (installed) Ok(Unit) else Err(failure)
        }.bind()
    }.bind()
}

/** The switch of the version in use from fs's docs/folders-and-moves.md, as written there. */
internal suspend fun AppDirs.useVersion(
    name: String,
    version: String,
): EmptyResult<FileError> = result {
    val locks = (cache / "locks").writer().bind()
    val packages = (cache / "packages").writer().bind()
    locks.awaitLock("$name/current.lock", wait = 30.seconds) {
        packages.switchSymlink("$name/current", Path(version)).bind()
    }.bind()
}
