package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ChangesStartTest {
    private val dir = scratchDir()
    private val config = dir / "app.toml"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    private val loads = mutableListOf<Result<FileInfo, FileError>>()
    private var loadsWhenServing: List<Result<FileInfo, FileError>>? = null

    private fun load(change: Result<FileInfo, FileError>) {
        loads += change
    }

    private suspend fun serve() {
        loadsWhenServing = loads.toList()
        // Time for the collector to run, so a first answer that came twice would show.
        delay(200.milliseconds)
        currentCoroutineContext().job.cancelChildren()
    }

    @Test
    fun `an undispatched collector loads the file once before the code after launch runs`() {
        config.writeText("a = 1").assertSuccess()

        runBlocking {
            launch(start = CoroutineStart.UNDISPATCHED) { config.changes().collect { load(it) } }
            serve()
        }

        val first = listOf(config.info())
        assertEquals(first, loadsWhenServing)
        assertEquals(first, loads)
    }
}
