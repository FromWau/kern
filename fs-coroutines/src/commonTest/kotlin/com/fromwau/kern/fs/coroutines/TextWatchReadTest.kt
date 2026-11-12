package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.exists
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// textChanges under TextWatch.Read waits on virtual time here, so each test says exactly when the file is read.
@OptIn(ExperimentalCoroutinesApi::class)
class TextWatchReadTest {
    private val dir = scratchDir()
    private val file = dir / "stat"
    private val interval = 1.seconds

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private fun TestScope.texts(): ReceiveChannel<Result<String, FileError>> {
        val texts = file.textChanges(maxBytes = 1_024, watch = TextWatch.Read(interval)).produceIn(backgroundScope)
        runCurrent()
        return texts
    }

    private fun TestScope.wait(time: Duration) {
        advanceTimeBy(time)
        runCurrent()
    }

    private fun ReceiveChannel<Result<String, FileError>>.next() = tryReceive().getOrNull()

    private fun ReceiveChannel<Result<String, FileError>>.nothing() = assertNull(next())

    /** Writes [text] and gives the file back the modification time [at], as procfs keeps one while its text moves. */
    private fun writeStill(
        text: String,
        at: Instant = STILL,
    ) {
        file.writeText(text, modifiedAt = at).assertSuccess()
    }

    @Test
    fun `the first answer is the file's text at once`() = runTest {
        writeStill("cpu 1")

        assertEquals(Ok("cpu 1"), texts().next())
    }

    @Test
    fun `new text of the same size and modification time is the next answer once interval has passed`() = runTest {
        writeStill("cpu 1")
        val texts = texts()
        assertEquals(Ok("cpu 1"), texts.next())

        writeStill("cpu 2")
        val info = file.info().assertSuccess()
        assertEquals(5, info.size)
        assertEquals(STILL, info.modifiedAt)
        wait(interval - 1.milliseconds)
        texts.nothing()
        wait(1.milliseconds)

        assertEquals(Ok("cpu 2"), texts.next())
    }

    @Test
    fun `text that stays as it was is no answer however many reads pass`() = runTest {
        writeStill("cpu 1")
        val texts = texts()
        assertEquals(Ok("cpu 1"), texts.next())

        wait(interval * 5)

        texts.nothing()
    }

    @Test
    fun `a missing file is NotFound once and its text once it is back`() = runTest {
        writeStill("cpu 1")
        val texts = texts()
        assertEquals(Ok("cpu 1"), texts.next())

        file.delete().assertSuccess()
        wait(interval)
        assertEquals(Err(FileError.NotFound(file)), texts.next())
        wait(interval * 3)
        texts.nothing()

        writeStill("cpu 1")
        wait(interval)
        assertEquals(Ok("cpu 1"), texts.next())
    }

    @Test
    fun `a file past maxBytes is TooLarge and its text once it fits again`() = runTest {
        writeStill("c".repeat(1_025))
        val texts = texts()
        assertIs<FileError.TooLarge>((texts.next() as? Result.Error)?.error)

        writeStill("cpu 1")
        wait(interval)

        assertEquals(Ok("cpu 1"), texts.next())
    }

    @Test
    fun `an interval that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { val _ = TextWatch.Read(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { val _ = TextWatch.Read(-interval) }
    }

    // procfs keeps size, modification time and change time still while the text moves, which is what this is for.
    // The kernel's idle counters in /proc/stat move every tick, so a few reads in real time see them move.
    @Test
    fun `proc stat gives a new answer on each of a few reads`() = runTest {
        val stat = Path("/proc/stat")
        if (!stat.exists()) return@runTest

        val answers = withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { stat.textChanges(maxBytes = 1_048_576, watch = TextWatch.Read(100.milliseconds)).take(3).toList() }
        }

        assertEquals(3, answers.distinct().size, "answers: $answers")
        assertTrue(answers.all { it is Result.Success }, "answers: $answers")
    }

    private companion object {
        val STILL = Instant.fromEpochSeconds(981_173_106)
    }
}
