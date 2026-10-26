package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PollChangesTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"
    private val every = 1.seconds

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private fun TestScope.answers(): ReceiveChannel<Result<FileInfo, FileError>> {
        val answers = file.pollChanges(every).produceIn(backgroundScope)
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
    fun `the first answer is the file's info at once`() = runTest {
        file.writeText("a = 1").assertSuccess()

        val answers = answers()

        assertEquals(5, answers.size())
    }

    @Test
    fun `a change is the next answer once every has passed`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val answers = answers()
        assertEquals(5, answers.size())

        file.writeText("a = 10").assertSuccess()
        wait(every - 1.milliseconds)
        assertNull(answers.tryReceive().getOrNull())
        wait(1.milliseconds)

        assertEquals(6, answers.size())
    }

    @Test
    fun `nothing that changed is no answer`() = runTest {
        file.writeText("a = 1").assertSuccess()
        val answers = answers()
        assertEquals(5, answers.size())

        wait(every * 5)

        assertNull(answers.tryReceive().getOrNull())
    }

    @Test
    fun `it opens no system watch`() = runTest {
        val before = openWatchDescriptors() ?: return@runTest
        file.writeText("a = 1").assertSuccess()

        val answers = answers()
        assertEquals(5, answers.size())

        assertEquals(before, openWatchDescriptors())
    }

    @Test
    fun `an every that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { val _ = file.pollChanges(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { val _ = file.pollChanges(-every) }
    }
}
