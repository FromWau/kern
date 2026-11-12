package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.permissions
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FileWatcherTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"
    private val watcher = FileWatcher(file)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    /** Runs [block] while [flow] is collected in real time, with its answers in the channel [block] receives. */
    private fun <T> answering(flow: Flow<T>, block: suspend Channel<T>.() -> Unit) = runTest {
        withContext(Dispatchers.Default) {
            coroutineScope {
                val answers = Channel<T>(Channel.UNLIMITED)
                val collector = launch { flow.collect { answers.send(it) } }
                try {
                    withTimeout(10.seconds) { answers.block() }
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun <T> Channel<T>.quietFor(): T? = withTimeoutOrNull(500.milliseconds) { receive() }

    @Test
    fun `collect answers the file's info at once and again after a write`() {
        file.writeText("a = 1").assertSuccess()
        val sizes = Channel<Long?>(Channel.UNLIMITED)

        runTest {
            withContext(Dispatchers.Default) {
                val collector = launch { watcher.collect { sizes.send(it.assertSuccess().size) } }
                try {
                    withTimeout(10.seconds) {
                        assertEquals(5, sizes.receive())
                        file.writeText("a = 10").assertSuccess()
                        while (sizes.receive() != 6L) {
                            // a repeat of the old info before the write lands
                        }
                    }
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `a watched folder's mode changing answers the same info again but not in metadata`() {
        if (dir.permissions().assertSuccess() == null) return
        file.writeText("a = 1").assertSuccess()

        answering(watcher.changes) {
            val first = receive()
            dir.chmod(0b111_111_111).assertSuccess()
            assertEquals(first, receive())
        }
        answering(watcher.metadata) {
            receive()
            dir.chmod(0b111_101_101).assertSuccess()
            assertNull(quietFor())
        }
    }

    @Test
    fun `text answers the file's text and again only when it differs`() {
        file.writeText("a = 1").assertSuccess()

        answering(watcher.text(maxBytes = 64)) {
            assertEquals(Ok("a = 1"), receive())
            file.chmod(0b110_000_000).assertSuccess()
            assertNull(quietFor())
            file.writeText("a = 2").assertSuccess()
            assertEquals(Ok("a = 2"), receive())
        }
    }
}
