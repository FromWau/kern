package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.RefusedBecause
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

// pollTextChanges looks on virtual time here, so each test says exactly when the file is read.
@OptIn(ExperimentalCoroutinesApi::class)
class TextChangesTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"
    private val every = 1.seconds

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private fun TestScope.texts(): ReceiveChannel<Result<String, FileError>> {
        val texts = file.pollTextChanges(maxBytes = 1_024, trusted = true, every = every).produceIn(backgroundScope)
        runCurrent()
        return texts
    }

    private fun TestScope.nextLook() {
        advanceTimeBy(every)
        runCurrent()
    }

    private fun ReceiveChannel<Result<String, FileError>>.nothing() = assertNull(tryReceive().getOrNull())

    private fun writeConfig(text: String) {
        file.writeText(text, ownerOnly = true).assertSuccess()
    }

    /** Whether this platform keeps permissions, which leaves something to observe in a test about them. */
    private fun hasPermissions(): Boolean = dir.permissions().assertSuccess() != null

    @Test
    fun `the first answer is the file's text`() = runTest {
        writeConfig("a = 1")

        assertEquals(Ok("a = 1"), texts().tryReceive().getOrNull())
    }

    @Test
    fun `a chmod that leaves the text as it was is no answer`() = runTest {
        writeConfig("a = 1")
        val texts = texts()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())

        file.chmod(0b100_000_000).assertSuccess()
        nextLook()

        texts.nothing()
    }

    @Test
    fun `a file others may write is Refused once and its text again once it is yours alone`() = runTest {
        if (!hasPermissions()) return@runTest
        writeConfig("a = 1")
        val texts = texts()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())

        file.chmod(0b110_110_110).assertSuccess()
        nextLook()
        val refused = assertIs<FileError.Refused>((texts.tryReceive().getOrNull() as? Result.Error)?.error)
        assertEquals(RefusedBecause.OthersMayWrite, refused.why)

        file.chmod(0b110_000_000).assertSuccess()
        nextLook()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())
    }

    @Test
    fun `a missing file is NotFound once and its text once it is back`() = runTest {
        writeConfig("a = 1")
        val texts = texts()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())

        file.delete().assertSuccess()
        nextLook()
        assertEquals(Err(FileError.NotFound(file)), texts.tryReceive().getOrNull())

        writeConfig("a = 1")
        nextLook()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())
    }

    @Test
    fun `a refused file that changes again is Refused again`() = runTest {
        if (!hasPermissions()) return@runTest
        writeConfig("a = 1")
        file.chmod(0b110_110_110).assertSuccess()
        val texts = texts()
        assertIs<FileError.Refused>((texts.tryReceive().getOrNull() as? Result.Error)?.error)

        file.chmod(0b110_000_110).assertSuccess()
        nextLook()

        val again = assertIs<FileError.Refused>((texts.tryReceive().getOrNull() as? Result.Error)?.error)
        assertEquals(RefusedBecause.OthersMayWrite, again.why)
    }

    @Test
    fun `the same text written again is no answer`() = runTest {
        writeConfig("a = 1")
        val texts = texts()
        assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())

        writeConfig("a = 1")
        nextLook()

        texts.nothing()
    }

    @Test
    fun `a polled change in a folder others may write is Refused and loads once the folder is yours alone`() =
        runTest {
            if (!hasPermissions()) return@runTest
            writeConfig("a = 1")
            val texts = texts()
            assertEquals(Ok("a = 1"), texts.tryReceive().getOrNull())

            dir.chmod(0b111_111_111).assertSuccess()
            writeConfig("a = 2")
            nextLook()
            val refused = assertIs<FileError.Refused>((texts.tryReceive().getOrNull() as? Result.Error)?.error)
            assertEquals(dir, refused.path)

            dir.chmod(0b111_101_101).assertSuccess()
            nextLook()
            assertEquals(Ok("a = 2"), texts.tryReceive().getOrNull())
            texts.nothing()
        }

    @Test
    fun `a file past maxBytes is TooLarge`() = runTest {
        writeConfig("a".repeat(1_025))

        assertIs<FileError.TooLarge>((texts().tryReceive().getOrNull() as? Result.Error)?.error)
    }

    // The system's watch wakes it here, so this one runs on Dispatchers.Default in real time.
    @Test
    fun `a system-watched change in a folder others may write is Refused and loads once the folder is yours alone`() =
        runTest {
            if (!hasPermissions()) return@runTest
            writeConfig("a = 1")
            val texts = Channel<Result<String, FileError>>(Channel.UNLIMITED)
            withContext(Dispatchers.Default) {
                val watch = launch { file.textChanges(maxBytes = 1_024, trusted = true).collect { texts.send(it) } }
                assertEquals(Ok("a = 1"), withTimeout(10.seconds) { texts.receive() })

                dir.chmod(0b111_111_111).assertSuccess()
                writeConfig("a = 2")
                val refused = withTimeout(10.seconds) { texts.receive() }
                assertEquals(dir, assertIs<FileError.Refused>((refused as? Result.Error)?.error).path)

                dir.chmod(0b111_101_101).assertSuccess()
                assertEquals(Ok("a = 2"), withTimeout(10.seconds) { texts.receivePastRefusalsOf(dir) })
                watch.cancelAndJoin()
            }
        }

    /**
     * The next answer that is not a refusal naming [folder]. A watch that reads once between the chmod and the write,
     * and again before the folder is narrowed back to `755`, answers a refusal for each, the second for the new file.
     */
    private suspend fun ReceiveChannel<Result<String, FileError>>.receivePastRefusalsOf(
        folder: Path,
    ): Result<String, FileError> {
        while (true) {
            val next = receive()
            val refused = (next as? Result.Error)?.error as? FileError.Refused
            if (refused?.path != folder) return next
        }
    }

    /** The reload from the README, as written there, with [load] and [println] handed in. */
    private suspend fun reload(
        config: Path,
        load: (String) -> Unit,
        println: (String) -> Unit,
    ) {
        config.textChanges(maxBytes = 1_048_576, trusted = true).collect { text ->
            text.onSuccess { load(it) }.onError { println("$config: $it") }
        }
    }

    // The system's watch wakes it here, so this one runs on Dispatchers.Default in real time.
    @Test
    fun `the README's reload loads the text and the new text once it changes`() = runTest {
        writeConfig("a = 1")
        val loads = Channel<String>(Channel.UNLIMITED)
        val printed = mutableListOf<String>()
        withContext(Dispatchers.Default) {
            val watch = launch { reload(file, { loads.trySend(it).getOrThrow() }, printed::add) }
            assertEquals("a = 1", withTimeout(10.seconds) { loads.receive() })

            writeConfig("a = 2")

            assertEquals("a = 2", withTimeout(10.seconds) { loads.receive() })
            watch.cancelAndJoin()
        }
        assertEquals(emptyList(), printed)
    }
}
