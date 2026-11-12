package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.canonicalPath
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.moveTo
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.switchSymlinkTo
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The operating system's watch is replaced here, so these run on virtual time and say when changes() looks.
@OptIn(ExperimentalCoroutinesApi::class)
class ChangesFallbackTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"
    private val pollEvery = 1.seconds

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    /** A watch whose wake-ups the test sends. */
    private class FakeWaker : FolderWaker {
        val wakes = Channel<Wake>(Channel.UNLIMITED)
        var closed = false

        override suspend fun next(): Wake = wakes.receive()

        override fun close() {
            closed = true
        }
    }

    /**
     * Hands out [watches] in turn, [FolderWatch.Refused] once they run out, and counts the calls; [onCall] runs with
     * each call's number before it answers.
     */
    private class Opener(
        vararg watches: FolderWatch,
        private val onCall: (Int) -> Unit = {},
    ) : (Map<Path, Set<String>>) -> FolderWatch {
        private val left = ArrayDeque(watches.toList())
        var calls = 0
        val asked = mutableListOf<Map<Path, Set<String>>>()

        override fun invoke(folders: Map<Path, Set<String>>): FolderWatch {
            calls++
            asked += folders
            onCall(calls)
            return left.removeFirstOrNull() ?: FolderWatch.Refused
        }
    }

    /** [folder] as the watch is asked for it, every link on the way resolved. */
    private fun real(folder: Path): Path = folder.canonicalPath().assertSuccess()

    private fun TestScope.answers(opener: Opener, path: Path = file): ReceiveChannel<Result<FileInfo, FileError>> {
        val answers = path.changes(pollEvery, opener).produceIn(backgroundScope)
        runCurrent()
        return answers
    }

    private fun ReceiveChannel<Result<FileInfo, FileError>>.size(): Long? =
        (tryReceive().getOrNull() as? Result.Success)?.value?.size

    private fun TestScope.wait(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    @Test
    fun `it watches the file's folder for the file's name`() = runTest {
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        assertEquals(listOf(mapOf(real(dir) to setOf("app.toml"))), opener.asked)
    }

    /** `app.toml` a link to `dotfiles/app.toml`, which holds `a = 1`. */
    private fun linkToDotfiles(): Path {
        val target = dir / "dotfiles" / "app.toml"
        target.writeText("a = 1").assertSuccess()
        file.createSymlink(Path("dotfiles/app.toml")).assertSuccess()
        return target
    }

    @Test
    fun `a link is watched in its own folder and in the folder of the file it leads to`() = runTest {
        val _ = linkToDotfiles()
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        val folders = mapOf(real(dir) to setOf("app.toml"), real(dir / "dotfiles") to setOf("app.toml"))
        assertEquals(listOf(folders), opener.asked)
    }

    /** `app.toml` the first of [links] links in a row, the last leading to `dotfiles/app.toml`. */
    private fun chainOf(links: Int) {
        (dir / "dotfiles" / "app.toml").writeText("a = 1").assertSuccess()
        val names = listOf("app.toml") + (1 until links).map { "link$it" }
        names.zipWithNext().forEach { (link, next) -> (dir / link).createSymlink(Path(next)).assertSuccess() }
        (dir / names.last()).createSymlink(Path("dotfiles/app.toml")).assertSuccess()
    }

    @Test
    fun `a chain of 40 links is watched in each folder it passes through once`() = runTest {
        chainOf(40)
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        val links = setOf("app.toml") + (1 until 40).map { "link$it" }
        assertEquals(listOf(mapOf(real(dir) to links, real(dir / "dotfiles") to setOf("app.toml"))), opener.asked)
    }

    @Test
    fun `a chain of 40 links over 41 folders is one watch`() = runTest {
        (dir / "dotfiles" / "app.toml").writeText("a = 1").assertSuccess()
        repeat(40) { index ->
            val next = if (index == 39) "../dotfiles/app.toml" else "../f${index + 1}/app.toml"
            (dir / "f$index").createDirectories().assertSuccess()
            (dir / "f$index" / "app.toml").createSymlink(Path(next)).assertSuccess()
        }
        val opener = Opener(FakeWaker())

        val _ = answers(opener, dir / "f0" / "app.toml")

        assertEquals(1, opener.calls)
        val folders = (0 until 40).map { real(dir / "f$it") } + real(dir / "dotfiles")
        assertEquals(folders, opener.asked.single().keys.toList())
    }

    @Test
    fun `a folder the chain spells two ways is one watch`() = runTest {
        (dir / "app.real.toml").writeText("a = 1").assertSuccess()
        file.createSymlink(Path("./app.real.toml")).assertSuccess()
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        assertEquals(listOf(mapOf(real(dir) to setOf("app.toml", "app.real.toml"))), opener.asked)
    }

    /**
     * `app.toml` a link to `mid/app.toml`, itself a link to `dotfiles/app.toml`, which holds `a = 1`, beside
     * `other/app.toml`, which holds `a = 22`.
     */
    private fun chainThroughMid() {
        (dir / "dotfiles" / "app.toml").writeText("a = 1").assertSuccess()
        (dir / "other" / "app.toml").writeText("a = 22").assertSuccess()
        (dir / "mid").createDirectories().assertSuccess()
        (dir / "mid" / "app.toml").createSymlink(Path("../dotfiles/app.toml")).assertSuccess()
        file.createSymlink(Path("mid/app.toml")).assertSuccess()
    }

    @Test
    fun `a link in the middle of a chain is watched in its own folder`() = runTest {
        chainThroughMid()
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        val folders = listOf(dir, dir / "mid", dir / "dotfiles")
        assertEquals(listOf(folders.associate { real(it) to setOf("app.toml") }), opener.asked)
    }

    @Test
    fun `a middle link pointed elsewhere answers the new file and moves the watch`() = runTest {
        chainThroughMid()
        val first = FakeWaker()
        val opener = Opener(first, FakeWaker())
        val answers = answers(opener)
        assertEquals(5, answers.size())

        (dir / "mid" / "app.toml").switchSymlinkTo(Path("../other/app.toml")).assertSuccess()
        first.wakes.send(Wake.Changed)
        runCurrent()

        assertEquals(6, answers.size())
        assertTrue(first.closed)
        assertEquals(listOf(dir, dir / "mid", dir / "other").map { real(it) }, opener.asked.last().keys.toList())
        assertEquals(2, opener.calls)
    }

    @Test
    fun `a folder link on the way pointed elsewhere is found at the next quiet wake and moves the watch`() = runTest {
        (dir / "releases" / "v1" / "config.toml").writeText("a = 1").assertSuccess()
        (dir / "releases" / "v2" / "config.toml").writeText("a = 22").assertSuccess()
        val current = dir / "app" / "current"
        (dir / "app").createDirectories().assertSuccess()
        current.createSymlink(Path("../releases/v1")).assertSuccess()
        val first = FakeWaker()
        val opener = Opener(first, FakeWaker())
        val answers = answers(opener, current / "config.toml")
        assertEquals(5, answers.size())

        current.switchSymlinkTo(Path("../releases/v2")).assertSuccess()
        first.wakes.send(Wake.Quiet)
        runCurrent()

        assertEquals(6, answers.size())
        assertTrue(first.closed)
        assertEquals(listOf(mapOf(real(dir / "releases" / "v2") to setOf("config.toml"))), opener.asked.drop(1))
    }

    @Test
    fun `a quiet wake with nothing moved is no answer and keeps the watch`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val opener = Opener(waker)
        val answers = answers(opener)
        assertEquals(5, answers.size())

        waker.wakes.send(Wake.Quiet)
        runCurrent()

        assertNull(answers.tryReceive().getOrNull())
        assertFalse(waker.closed)
        assertEquals(1, opener.calls)
    }

    @Test
    fun `a chain of 41 links goes round and is watched in its own folder alone`() = runTest {
        chainOf(41)
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        assertEquals(listOf(mapOf(real(dir) to setOf("app.toml"))), opener.asked)
    }

    @Test
    fun `a dangling link is watched in the folder its target would be in`() = runTest {
        (dir / "dotfiles").createDirectories().assertSuccess()
        file.createSymlink(Path("dotfiles/app.toml")).assertSuccess()
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        val folders = mapOf(real(dir) to setOf("app.toml"), real(dir / "dotfiles") to setOf("app.toml"))
        assertEquals(listOf(folders), opener.asked)
    }

    @Test
    fun `a link to a file in its own folder is one watch for both names`() = runTest {
        (dir / "app.real.toml").writeText("a = 1").assertSuccess()
        file.createSymlink(Path("app.real.toml")).assertSuccess()
        val opener = Opener(FakeWaker())

        val _ = answers(opener)

        assertEquals(listOf(mapOf(real(dir) to setOf("app.toml", "app.real.toml"))), opener.asked)
    }

    @Test
    fun `a link pointed elsewhere while its watch is set is watched where it leads now`() = runTest {
        val _ = linkToDotfiles()
        (dir / "other" / "app.toml").writeText("a = 22").assertSuccess()
        val first = FakeWaker()
        val opener = Opener(first, FakeWaker()) { call ->
            if (call == 1) file.switchSymlinkTo(Path("other/app.toml")).assertSuccess()
        }

        val answers = answers(opener)

        assertEquals(listOf(real(dir), real(dir / "other")), opener.asked.last().keys.toList())
        assertTrue(first.closed)
        assertEquals(6, answers.size())
    }

    @Test
    fun `a refused watch on a link's folders looks every pollEvery`() = runTest {
        val target = linkToDotfiles()
        val opener = Opener(FolderWatch.Refused)
        val answers = answers(opener)
        assertEquals(5, answers.size())

        target.writeText("a = 10").assertSuccess()
        wait(pollEvery)
        assertEquals(6, answers.size())
        wait(pollEvery * 5)
        assertEquals(1, opener.calls)
    }

    @Test
    fun `cancelling a collection that polls after a refusal stops its lookups`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val polls = file.changes(pollEvery, Opener(FolderWatch.Refused))
        val collector = backgroundScope.launch { polls.collect {} }
        runCurrent()

        collector.cancel()
        wait(pollEvery * 5)

        assertTrue(collector.isCompleted)
    }

    @Test
    fun `a refused watch looks every pollEvery and asks for no watch again`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val opener = Opener(FolderWatch.Refused)
        val answers = answers(opener)
        assertEquals(5, answers.size())

        file.writeText("a = 10").assertSuccess()
        wait(pollEvery - 1.milliseconds)
        assertNull(answers.tryReceive().getOrNull())
        wait(1.milliseconds)
        assertEquals(6, answers.size())
        wait(pollEvery * 5)

        assertEquals(1, opener.calls)
    }

    @Test
    fun `a refused watch on a folder that is not there is not asked for again`() = runTest {
        val config = dir / "config" / "app.toml"
        val opener = Opener(FolderWatch.Refused, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())

        wait(pollEvery * 5)

        assertEquals(1, opener.calls)
    }

    @Test
    fun `a missing folder is waited for in the nearest folder that is there without lookups on the timer`() = runTest {
        val folder = dir / "config"
        val config = folder / "app.toml"
        val above = FakeWaker()
        val opener = Opener(FolderWatch.Missing, above, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())
        assertEquals(mapOf(real(dir) to setOf("config")), opener.asked[1])

        wait(pollEvery * 5)
        assertEquals(2, opener.calls)

        config.writeText("a = 1").assertSuccess()
        above.wakes.send(Wake.Changed)
        runCurrent()

        assertEquals(5, answers.size())
        assertTrue(above.closed)
        assertEquals(mapOf(real(folder) to setOf("app.toml")), opener.asked[2])
    }

    @Test
    fun `a look while a missing folder is awaited keeps waiting without asking for the watch again`() = runTest {
        val config = dir / "config" / "app.toml"
        val above = FakeWaker()
        val opener = Opener(FolderWatch.Missing, above, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())

        above.wakes.send(Wake.Look)
        runCurrent()

        assertEquals(2, opener.calls)
        assertFalse(above.closed)
    }

    @Test
    fun `a missing folder two levels down is waited for one folder at a time`() = runTest {
        val config = dir / "a" / "b" / "app.toml"
        val aboveA = FakeWaker()
        val aboveB = FakeWaker()
        val opener = Opener(FolderWatch.Missing, aboveA, FolderWatch.Missing, aboveB, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())

        (dir / "a").createDirectories().assertSuccess()
        aboveA.wakes.send(Wake.Look)
        runCurrent()
        assertEquals(mapOf(real(dir / "a") to setOf("b")), opener.asked[3])

        config.writeText("a = 1").assertSuccess()
        aboveB.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(5, answers.size())
        assertEquals(5, opener.calls)
    }

    @Test
    fun `a folder made while the watch above it is set is watched at once`() = runTest {
        val config = dir / "config" / "app.toml"
        val opener = Opener(FolderWatch.Missing, FakeWaker(), FakeWaker()) { call ->
            if (call == 2) config.writeText("a = 1").assertSuccess()
        }

        val answers = answers(opener, config)

        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())
        assertEquals(5, answers.size())
        assertEquals(3, opener.calls)
    }

    @Test
    fun `an event looks at once and the watch goes on`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val opener = Opener(waker)
        val answers = answers(opener)
        assertEquals(5, answers.size())

        file.writeText("a = 10").assertSuccess()
        waker.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(6, answers.size())

        file.writeText("a = 100").assertSuccess()
        waker.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(7, answers.size())
        assertEquals(1, opener.calls)
        assertFalse(waker.closed)
    }

    @Test
    fun `an overflow looks at once and opens the watch again`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val again = FakeWaker()
        val opener = Opener(waker, again)
        val answers = answers(opener)
        assertEquals(5, answers.size())

        file.writeText("a = 10").assertSuccess()
        waker.wakes.send(Wake.Overflow)
        runCurrent()
        assertEquals(6, answers.size())
        assertTrue(waker.closed)
        assertEquals(2, opener.calls)

        file.writeText("a = 100").assertSuccess()
        again.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(7, answers.size())
    }

    @Test
    fun `a folder above swapped for another at its path is found at the next quiet wake and moves the watch`() =
        runTest {
            val config = dir / "top" / "mid" / "app.toml"
            config.writeText("a = 1").assertSuccess()
            (dir / "new" / "mid" / "app.toml").writeText("a = 10").assertSuccess()
            val waker = FakeWaker()
            val opener = Opener(waker, FakeWaker())
            val answers = answers(opener, config)
            assertEquals(5, answers.size())

            (dir / "top").moveTo(dir / "top.old").assertSuccess()
            (dir / "new").moveTo(dir / "top").assertSuccess()
            waker.wakes.send(Wake.Quiet)
            runCurrent()

            assertEquals(6, answers.size())
            assertTrue(waker.closed)
            assertEquals(2, opener.calls)
        }

    @Test
    fun `a watch refused once its folder is gone waits for the folder instead of looking on the timer`() = runTest {
        val folder = dir / "config"
        val config = folder / "app.toml"
        config.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val opener = Opener(waker, FolderWatch.Missing, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(5, answers.size())

        folder.deleteRecursively().assertSuccess()
        waker.wakes.send(Wake.Refused)
        runCurrent()

        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())
        assertEquals(3, opener.calls)
        assertEquals(mapOf(real(dir) to setOf("config")), opener.asked[2])
    }

    @Test
    fun `a link into a missing folder pointed elsewhere is found at the next quiet wake`() = runTest {
        val link = dir / "link"
        (dir / "real").writeText("a = 1").assertSuccess()
        link.createSymlink(dir / "missing" / "app.toml").assertSuccess()
        val above = FakeWaker()
        val opener = Opener(FolderWatch.Missing, above, FakeWaker())
        val answers = answers(opener, link)
        assertEquals(Err(FileError.NotFound(link)), answers.tryReceive().getOrNull())

        link.switchSymlinkTo(dir / "real").assertSuccess()
        above.wakes.send(Wake.Quiet)
        runCurrent()

        assertEquals(5, answers.size())
        assertTrue(above.closed)
    }

    @Test
    fun `a folder link whose target folder is made later is found at the next quiet wake`() = runTest {
        (dir / "releases").createDirectories().assertSuccess()
        (dir / "current").createSymlink(Path("releases/v1")).assertSuccess()
        val config = dir / "current" / "app.toml"
        val above = FakeWaker()
        val opener = Opener(FolderWatch.Missing, above, FakeWaker())
        val answers = answers(opener, config)
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())

        (dir / "releases" / "v1" / "app.toml").writeText("a = 1").assertSuccess()
        above.wakes.send(Wake.Quiet)
        runCurrent()

        assertEquals(5, answers.size())
        assertTrue(above.closed)
    }

    @Test
    fun `a change the system tells of answers again though the file looks the same`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val answers = answers(Opener(waker))
        val first = answers.tryReceive().getOrNull()

        waker.wakes.send(Wake.Changed)
        runCurrent()

        assertEquals(first, answers.tryReceive().getOrNull())
        assertNull(answers.tryReceive().getOrNull())
    }

    @Test
    fun `an overflow answers again though the file looks the same`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val answers = answers(Opener(waker))
        val first = answers.tryReceive().getOrNull()

        waker.wakes.send(Wake.Overflow)
        runCurrent()

        assertEquals(first, answers.tryReceive().getOrNull())
    }

    @Test
    fun `a look answers only what differs`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val answers = answers(Opener(waker))
        assertEquals(5, answers.size())

        waker.wakes.send(Wake.Look)
        runCurrent()
        assertNull(answers.tryReceive().getOrNull())

        file.writeText("a = 10").assertSuccess()
        waker.wakes.send(Wake.Look)
        runCurrent()
        assertEquals(6, answers.size())
    }

    @Test
    fun `a lookup on the timer answers only what differs`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val answers = answers(Opener(FolderWatch.Refused))
        assertEquals(5, answers.size())

        wait(pollEvery * 3)

        assertNull(answers.tryReceive().getOrNull())
    }

    @Test
    fun `a watched folder's mode changing answers again and a file edit answers once`() = runTest {
        if (dir.permissions().assertSuccess() == null) return@runTest
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val answers = answers(Opener(waker))
        val first = answers.tryReceive().getOrNull()

        dir.chmod(0b111_111_111).assertSuccess()
        waker.wakes.send(Wake.Look)
        runCurrent()
        assertEquals(first, answers.tryReceive().getOrNull())

        file.writeText("a = 10").assertSuccess()
        waker.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(6, answers.size())
        assertNull(answers.tryReceive().getOrNull())

        dir.chmod(0b111_101_101).assertSuccess()
        waker.wakes.send(Wake.Look)
        waker.wakes.send(Wake.Look)
        runCurrent()
        assertEquals(6, answers.size())
        assertNull(answers.tryReceive().getOrNull())
    }

    @Test
    fun `a folder gone closes the watch and waits in the folder above until it is back`() = runTest {
        val folder = dir / "config"
        val config = folder / "app.toml"
        config.writeText("a = 1").assertSuccess()
        val first = FakeWaker()
        val above = FakeWaker()
        val second = FakeWaker()
        val opener = Opener(first, FolderWatch.Missing, above, second)
        val answers = answers(opener, config)
        assertEquals(5, answers.size())

        folder.deleteRecursively().assertSuccess()
        first.wakes.send(Wake.FolderGone)
        runCurrent()
        assertEquals(Err(FileError.NotFound(config)), answers.tryReceive().getOrNull())
        assertTrue(first.closed)
        assertEquals(3, opener.calls)

        config.writeText("a = 10").assertSuccess()
        above.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(6, answers.size())
        assertEquals(4, opener.calls)

        config.writeText("a = 100").assertSuccess()
        second.wakes.send(Wake.Changed)
        runCurrent()
        assertEquals(7, answers.size())
    }

    @Test
    fun `a watch refused later closes it and looks every pollEvery from then on`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val waker = FakeWaker()
        val opener = Opener(waker, FakeWaker())
        val answers = answers(opener)
        assertEquals(5, answers.size())

        waker.wakes.send(Wake.Refused)
        runCurrent()
        assertTrue(waker.closed)

        file.writeText("a = 10").assertSuccess()
        wait(pollEvery)
        assertEquals(6, answers.size())
        wait(pollEvery * 5)
        assertEquals(1, opener.calls)
    }

    @Test
    fun `cancelling the collection closes the watch`() = runTest {
        val waker = FakeWaker()
        val answers = answers(Opener(waker))

        answers.cancel()
        runCurrent()

        assertTrue(waker.closed)
    }

    @Test
    fun `a collector that stops at the first answer closes the watch`() = runTest {
        val waker = FakeWaker()

        val _ = file.changes(pollEvery, Opener(waker)).first()

        assertTrue(waker.closed)
    }

    @Test
    fun `a fallback that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { val _ = Watch.System(Duration.ZERO) }
    }
}
