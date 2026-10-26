package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.WriteMode
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.moveTo
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.readSymlink
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// These wait for the real operating system's events, so they run on Dispatchers.Default in real time.
class ChangesTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private class Answers(private val channel: ReceiveChannel<Result<FileInfo, FileError>>) {
        /** The next answer, failing the test when none comes in time. */
        suspend fun next(): Result<FileInfo, FileError> = withTimeout(10.seconds) { channel.receive() }

        /** Waits for an answer [matching], skipping those before it. */
        suspend fun until(matching: (Result<FileInfo, FileError>) -> Boolean) {
            val _ = withTimeout(10.seconds) { channel.receiveAsFlow().first(matching) }
        }

        /** The answer that came within [wait], or null. */
        suspend fun within(wait: Duration): Result<FileInfo, FileError>? = withTimeoutOrNull(wait) { channel.receive() }
    }

    // Where the system watches folders, a lookup this rare shows each answer came from its events.
    private val eventsOnly =
        (openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker)?.use { 1.hours } ?: 1.seconds

    private fun watching(
        path: Path = file,
        pollEvery: Duration = eventsOnly,
        block: suspend Answers.() -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            coroutineScope {
                val channel = Channel<Result<FileInfo, FileError>>(Channel.UNLIMITED)
                val watch = launch { path.changes(pollEvery).collect { channel.send(it) } }
                try {
                    Answers(channel).block()
                } finally {
                    watch.cancelAndJoin()
                }
            }
        }
    }

    private fun Result<FileInfo, FileError>.size(): Long = assertSuccess().size

    private fun sizeIs(bytes: Long): (Result<FileInfo, FileError>) -> Boolean =
        { it is Result.Success && it.value.size == bytes }

    @Test
    fun `the first answer is the file's info at once`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
        }
    }

    @Test
    fun `the first answer for a missing file is NotFound`() = watching {
        assertEquals(Err(FileError.NotFound(file)), next())
    }

    @Test
    fun `a write by rename that changes the size is the next answer`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
            file.writeText("a = 10").assertSuccess()
            until(sizeIs(6))
        }
    }

    @Test
    fun `a watched folder's mode changing answers again and a file edit answers once`() {
        if (dir.permissions().assertSuccess() == null) return
        file.writeText("a = 1").assertSuccess()

        watching {
            val first = next()
            dir.chmod(0b111_111_111).assertSuccess()
            assertEquals(first, next())
            dir.chmod(0b111_101_101).assertSuccess()
            assertEquals(first, next())

            file.writeText("a = 10").assertSuccess()
            assertEquals(6, next().size())
            assertNull(within(500.milliseconds))
        }
    }

    @Test
    fun `a write in place that changes the size is the next answer`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
            file.writeText("a = 10", mode = WriteMode.InPlace).assertSuccess()
            until(sizeIs(6))
        }
    }

    @Test
    fun `an atomic replace by rename is the next answer`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
            file.writeText("a = 100", mode = WriteMode.Replace).assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `a chmod is an answer with the new permissions`() {
        file.writeText("a = 1").assertSuccess()
        file.chmod(0b110_100_100).assertSuccess()

        watching {
            val before = next().assertSuccess()
            // Windows keeps no permission bits to change.
            if (before.permissions == null) return@watching
            file.chmod(0b110_000_000).assertSuccess()
            val after = next().assertSuccess()
            assertNotEquals(before.permissions, after.permissions)
            assertEquals(before.size, after.size)
        }
    }

    @Test
    fun `a delete is NotFound once and a file made again is info once more`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
            file.delete().assertSuccess()
            assertEquals(Err(FileError.NotFound(file)), next())
            file.writeText("a = 10").assertSuccess()
            assertEquals(6, next().size())
        }
    }

    @Test
    fun `nothing comes while nothing changes`() {
        file.writeText("a = 1").assertSuccess()
        val sibling = dir / "other.toml"

        watching {
            assertEquals(5, next().size())
            sibling.writeText("b = 1").assertSuccess()
            assertNull(within(500.milliseconds))
        }
    }

    @Test
    fun `two quick writes end with an answer for the last`() {
        file.writeText("a = 1").assertSuccess()

        watching {
            assertEquals(5, next().size())
            file.writeText("a = 10").assertSuccess()
            file.writeText("a = 100").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `a file whose folder is missing at the start is NotFound and then its info once both are made`() {
        val folder = dir / "config"
        val config = folder / "app.toml"

        watching(config, pollEvery = 50.milliseconds) {
            assertEquals(Err(FileError.NotFound(config)), next())
            folder.createDirectories().assertSuccess()
            config.writeText("a = 1").assertSuccess()
            assertEquals(5, next().size())
            config.writeText("a = 10").assertSuccess()
            until(sizeIs(6))
        }
    }

    @Test
    fun `a file whose folder is deleted is NotFound and then its info once both are back`() {
        val folder = dir / "config"
        val config = folder / "app.toml"
        config.writeText("a = 1").assertSuccess()

        watching(config, pollEvery = 50.milliseconds) {
            assertEquals(5, next().size())
            folder.deleteRecursively().assertSuccess()
            assertEquals(Err(FileError.NotFound(config)), next())
            config.writeText("a = 10").assertSuccess()
            assertEquals(6, next().size())
            config.writeText("a = 100").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `a file whose folder is moved away is NotFound and then its info once both are back`() {
        val folder = dir / "config"
        val config = folder / "app.toml"
        config.writeText("a = 1").assertSuccess()

        watching(config, pollEvery = 50.milliseconds) {
            assertEquals(5, next().size())
            folder.moveTo(dir / "moved").assertSuccess()
            assertEquals(Err(FileError.NotFound(config)), next())
            config.writeText("a = 10").assertSuccess()
            assertEquals(6, next().size())
            config.writeText("a = 100").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `cancelling the collection closes what it watched with`() {
        if (openWatchDescriptors() == null) return
        file.writeText("a = 1").assertSuccess()
        // A first watch opens what the runtime opens once and keeps.
        watching { val _ = next() }
        val before = openWatchDescriptors()

        watching {
            assertEquals(5, next().size())
            assertNotEquals(before, openWatchDescriptors())
        }

        assertEquals(before, openWatchDescriptors())
    }
}

/**
 * How many descriptors a system watch could be holding open, or null where they cannot be listed. Open descriptors
 * are listed on Linux, for native and the JVM alike.
 */
internal fun openWatchDescriptors(): Int? {
    val descriptors = Path("/proc/self/fd")
    if (!SystemFileSystem.exists(descriptors)) return null
    return SystemFileSystem.list(descriptors).count {
        !runtimeOpensDescriptors || it.readSymlink().getOrNull()?.toString() == "anon_inode:inotify"
    }
}

/** Whether the runtime's own threads open and close descriptors while a test runs, as the JVM's do. */
internal expect val runtimeOpensDescriptors: Boolean
