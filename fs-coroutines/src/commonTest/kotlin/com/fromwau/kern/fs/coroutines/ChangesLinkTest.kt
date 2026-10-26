package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.switchSymlinkTo
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
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
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

// These wait for the real operating system's events, so they run on Dispatchers.Default in real time.
class ChangesLinkTest {
    private val dir = scratchDir()
    private val conf = (dir / "conf").also { it.createDirectories().assertSuccess() }
    private val dotfiles = (dir / "dotfiles").also { it.createDirectories().assertSuccess() }
    private val link = conf / "app.toml"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    // Where the system watches folders, a lookup this rare shows each answer came from its events.
    private val eventsOnly =
        (openFolderWaker(mapOf(dir to setOf("app.toml"))) as? FolderWaker)?.use { 1.hours } ?: 1.seconds

    private class Answers(private val channel: ReceiveChannel<Result<FileInfo, FileError>>) {
        suspend fun next(): Result<FileInfo, FileError> = withTimeout(10.seconds) { channel.receive() }

        suspend fun until(matching: (Result<FileInfo, FileError>) -> Boolean) {
            val _ = withTimeout(10.seconds) { channel.receiveAsFlow().first(matching) }
        }
    }

    private fun watching(
        path: Path = link,
        block: suspend Answers.() -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            coroutineScope {
                val channel = Channel<Result<FileInfo, FileError>>(Channel.UNLIMITED)
                val watch = launch { path.changes(eventsOnly).collect { channel.send(it) } }
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
    fun `an edit of a link's target in another folder is an answer`() {
        val target = dotfiles / "app.toml"
        target.writeText("a = 1").assertSuccess()
        link.createSymlink(Path("../dotfiles/app.toml")).assertSuccess()

        watching {
            assertEquals(5, next().size())
            target.writeText("a = 10").assertSuccess()
            until(sizeIs(6))
        }
    }

    @Test
    fun `a link pointed elsewhere is an answer and its new target's folder is watched from then on`() {
        val old = dotfiles / "app.toml"
        val other = (dir / "other").also { it.createDirectories().assertSuccess() }
        val new = other / "app.toml"
        old.writeText("a = 1").assertSuccess()
        new.writeText("a = 22").assertSuccess()
        link.createSymlink(old).assertSuccess()

        watching {
            assertEquals(5, next().size())
            link.switchSymlinkTo(new).assertSuccess()
            until(sizeIs(6))
            new.writeText("a = 333").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `an edit of a link's target in the link's own folder is an answer`() {
        val target = conf / "app.real.toml"
        target.writeText("a = 1").assertSuccess()
        link.createSymlink(Path("app.real.toml")).assertSuccess()

        watching {
            assertEquals(5, next().size())
            target.writeText("a = 10").assertSuccess()
            until(sizeIs(6))
        }
    }

    @Test
    fun `a middle link in a third folder pointed elsewhere answers the new file`() {
        val mid = (dir / "mid").also { it.createDirectories().assertSuccess() }
        val other = (dir / "other").also { it.createDirectories().assertSuccess() }
        (dotfiles / "app.toml").writeText("a = 1").assertSuccess()
        (other / "app.toml").writeText("a = 22").assertSuccess()
        (mid / "app.toml").createSymlink(Path("../dotfiles/app.toml")).assertSuccess()
        link.createSymlink(Path("../mid/app.toml")).assertSuccess()

        watching {
            assertEquals(5, next().size())
            (mid / "app.toml").switchSymlinkTo(Path("../other/app.toml")).assertSuccess()
            until(sizeIs(6))
            (other / "app.toml").writeText("a = 333").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `a folder link on the way pointed at another release answers edits of that release`() {
        val releases = (dir / "releases").also { it.createDirectories().assertSuccess() }
        (releases / "v1" / "config.toml").writeText("a = 1").assertSuccess()
        (releases / "v2" / "config.toml").writeText("a = 22").assertSuccess()
        val current = conf / "current"
        current.createSymlink(Path("../releases/v1")).assertSuccess()

        watching(current / "config.toml") {
            assertEquals(5, next().size())
            current.switchSymlinkTo(Path("../releases/v2")).assertSuccess()
            (releases / "v2" / "config.toml").writeText("a = 333").assertSuccess()
            until(sizeIs(7))
        }
    }

    @Test
    fun `an edit at the end of a chain of links is an answer`() {
        val real = (dir / "real").also { it.createDirectories().assertSuccess() }
        val target = real / "app.toml"
        target.writeText("a = 1").assertSuccess()
        (dotfiles / "app.toml").createSymlink(target).assertSuccess()
        link.createSymlink(dotfiles / "app.toml").assertSuccess()

        watching {
            assertEquals(5, next().size())
            target.writeText("a = 10").assertSuccess()
            until(sizeIs(6))
        }
    }
}
