package com.fromwau.kern.fs.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MissingDriveMingwTest {
    @Test
    fun `a file on a drive that is not there is looked up on the timer and watched nowhere else`() = runTest {
        val drive = ('D'..'Z').first { !SystemFileSystem.exists(Path("$it:/")) }
        val file = Path("$drive:/config/app.toml")
        val asked = mutableListOf<Map<Path, Set<String>>>()
        val answers = file.changes(1.seconds) { folders ->
            asked += folders
            if (folders.keys.all { it.toString().startsWith("$drive:") }) FolderWatch.Missing else FakeWaker()
        }.produceIn(backgroundScope)
        runCurrent()

        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(listOf(mapOf(Path("$drive:/config") to setOf("app.toml"))), asked)
        answers.cancel()
    }

    /** A watch that never wakes. */
    private class FakeWaker : FolderWaker {
        override suspend fun next(): Wake = kotlinx.coroutines.awaitCancellation()

        override fun close() {}
    }
}
