package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.openAppend
import com.fromwau.kern.logger.FlushError
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.Pending
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.map
import kotlinx.io.files.Path
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FileBatchJvmTest {
    private val logger = testLogger()
    private val dir = newTempDir()
    private val file = Path(dir, "app.log")
    private val appends: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val noSpace = FileError.WriteFailed(file, IoCause.NoSpace, IOException("No space left on device"))

    @AfterTest
    fun cleanup() {
        val _ = logger.close()
        dir.deleteTree()
    }

    /**
     * A sink whose appends are recorded in [appends]; [failAppend] decides per append whether it fails instead. The
     * index counts every append of the sink, across the handles it reopens after a failed one.
     */
    private fun recordedSink(
        onFailure: SinkFailure = SinkFailure.Retry(),
        capacity: Int = 10_000,
        beforeAppend: (index: Int) -> Unit = {},
        failAppend: (index: Int) -> Boolean = { false },
    ): FileSink {
        val index = AtomicInteger()
        return testFileSink(file, capacity = capacity, onFailure = onFailure) { path ->
            path.openAppend().map { Recorded(it, index, beforeAppend, failAppend) }
        }
    }

    private inner class Recorded(
        private val opened: AppendFile,
        private val index: AtomicInteger,
        private val beforeAppend: (Int) -> Unit,
        private val failAppend: (Int) -> Boolean,
    ) : AppendFile by opened {
        override fun append(bytes: ByteArray): EmptyResult<FileError> {
            val current = index.getAndIncrement()
            beforeAppend(current)
            if (failAppend(current)) return Err(noSpace)
            appends += bytes.decodeToString()
            return opened.append(bytes)
        }
    }

    /** Logs "first", holds its append until [queued] more entries are logged, then lets the sink go on. */
    private fun logBehindABusyFile(queued: Int, sink: (holdFirstAppend: (Int) -> Unit) -> FileSink) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val _ = logger.addOrFail(
            sink { index ->
                if (index == 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            },
        )
        logger.tag("app").i { "first" }
        check(entered.await(5, TimeUnit.SECONDS))
        repeat(queued) { logger.tag("app").i { "queued $it" } }
        release.countDown()
    }

    @Test
    fun `entries queued while the file is busy go out in one append of whole lines`() {
        logBehindABusyFile(5) { holdFirstAppend -> recordedSink(beforeAppend = holdFirstAppend) }

        assertEquals(Ok(Flushed.FullyWritten), logger.flush())
        assertEquals(listOf("first") + List(5) { "queued $it" }, file.messages())
        assertEquals(2, appends.size, "appends: $appends")
        assertEquals(5, appends[1].count { it == '\n' })
        assertTrue(appends[1].endsWith("\n"))
    }

    @Test
    fun `a failed batch append is retried whole and each line lands once`() {
        // The disk stays full until the test frees it, so the first flush sees the failure whichever comes first.
        val diskFull = AtomicBoolean(true)
        logBehindABusyFile(5) { holdFirstAppend ->
            recordedSink(
                beforeAppend = holdFirstAppend,
                failAppend = { it >= 1 && diskFull.get() },
            )
        }

        val installed = logger.sinks.value.single()
        val refused = SinkError.Dropped(noSpace)
        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.GaveUp(5, refused)))), logger.flush())
        diskFull.set(false)
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())
        assertEquals(listOf("first") + List(5) { "queued $it" }, file.messages())
        assertEquals(2, appends.size, "appends: $appends")
        assertEquals(0, installed.health.value.dropped)
    }

    @Test
    fun `a failed batch append under Drop loses the whole batch and counts each line`() {
        logBehindABusyFile(5) { holdFirstAppend ->
            recordedSink(
                onFailure = SinkFailure.Drop(backoff = Duration.ZERO),
                beforeAppend = holdFirstAppend,
                failAppend = { it == 1 },
            )
        }

        val installed = logger.sinks.value.single()
        assertEquals(Err(FlushError.Lost(mapOf(installed.name to 5L), emptyMap())), logger.flush())
        logger.tag("app").i { "after" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        val messages = file.messages()
        assertEquals("first", messages.first())
        assertContains(messages[1], "dropped 5 entries")
        assertEquals("after", messages.last())
        assertEquals(3, messages.size)
    }

    @Test
    fun `batched appends from many threads hold whole lines in each thread's order`() {
        val threads = 8
        val perThread = 5_000
        val _ = logger.addOrFail(recordedSink(capacity = threads * perThread))

        List(threads) { index ->
            thread {
                repeat(perThread) { seq -> logger.tag("t$index").i { "thread $index seq $seq" } }
            }
        }.forEach { it.join() }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush(30.seconds))

        val recorded = appends.toList()
        assertTrue(recorded.size < threads * perThread, "${recorded.size} appends for ${threads * perThread} lines")
        assertTrue(recorded.all { it.endsWith("\n") }, "an append ended inside a line")
        val lines = file.messages()
        assertEquals(threads * perThread, lines.size)
        lines.groupBy { it.substringBefore(" seq") }.forEach { (thread, mine) ->
            assertEquals(List(perThread) { "$thread seq $it" }, mine, thread)
        }
    }
}
