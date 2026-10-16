package com.fromwau.kern.logger.file

import com.fromwau.kern.logger.CloseError
import com.fromwau.kern.logger.InstalledSink
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Many threads logging to a file that is moved away or deleted every 150 ms, read back from every file it reached. */
class FileRotationJvmTest {
    private val dir = newTempDir()
    private val file = Path(dir, "app.log")

    @AfterTest
    fun cleanup() {
        dir.deleteTree()
    }

    @Test
    fun `with a small queue every dropped entry is reported by a gap entry`() {
        val run = rotate(capacity = 300)

        assertEquals(run.closedReportingEveryDrop(), run.closed)
        assertEquals(0, run.torn)
        assertEquals(run.dropped, run.gapCounts, "dropped by health against the gap entries written")
    }

    @Test
    fun `with room for every entry each one is written or counted as dropped exactly once`() {
        val run = rotate(capacity = 1_000_000)

        assertEquals(run.closedReportingEveryDrop(), run.closed)
        assertEquals(0, run.torn)
        assertEquals(run.dropped, run.gapCounts, "dropped by health against the gap entries written")
        assertEquals(run.logged, run.distinct + run.dropped, "entries found plus entries counted as dropped")
    }

    private class Run(
        val installed: InstalledSink,
        val closed: Any,
        val logged: Long,
        val distinct: Long,
        val dropped: Long,
        val gapCounts: Long,
        val torn: Int,
    ) {
        /** What a close that wrote everything else returns: every drop, reported as lost. */
        fun closedReportingEveryDrop(): EmptyResult<CloseError> =
            if (dropped == 0L) Ok(Unit) else Err(CloseError(mapOf(installed.name to listOf(SinkError.Lost(dropped)))))
    }

    private fun rotate(capacity: Int, threads: Int = 4, perThread: Int = 40_000): Run {
        val path = File(file.toString())
        val logger = testLogger()
        val sink = FileSink(file, format = LogFormat.JSON, capacity = capacity)
        val installed = logger.addOrFail(sink, SinkConfig(LogLevel.VERBOSE))
        // Lines of deleted files are read through a stream opened before the delete.
        val pieces = mutableListOf<List<String>>()
        val stop = AtomicBoolean(false)
        // An assumption failing on the rotator would end only that thread, so the test thread checks it after join.
        val renameRefused = AtomicBoolean(false)
        val rotator = thread {
            var round = 0
            while (!stop.get()) {
                Thread.sleep(150)
                if (!path.exists()) continue
                if (round % 2 == 0) {
                    if (!path.renameTo(File(path.parentFile, "moved-$round.log"))) {
                        renameRefused.set(true)
                        break
                    }
                } else {
                    FileInputStream(path).use { held ->
                        path.delete()
                        Thread.sleep(400)
                        synchronized(pieces) { pieces += held.bufferedReader().readLines() }
                    }
                }
                round++
            }
        }
        List(threads) { index ->
            thread {
                for (seq in 0 until perThread) {
                    logger.tag("t$index").i("thread" to index, "seq" to seq) { "entry" }
                    if (seq % 1_000 == 999) Thread.sleep(25)
                }
            }
        }.forEach { it.join() }
        stop.set(true)
        rotator.join()
        assumeFalse(renameRefused.get(), "this platform cannot move a file held open")
        val closed = logger.close(30.seconds)

        path.parentFile
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("moved-") }
            .forEach { pieces += it.readLines() }
        if (path.exists()) pieces += path.readLines()
        val seen = HashSet<Long>()
        var gapCounts = 0L
        var torn = 0
        // A gap entry written again into a new file after a delete is the same line twice.
        val gapLines = HashSet<String>()
        pieces.flatten().forEach { line ->
            val json = try {
                Json.parseToJsonElement(line).jsonObject
            } catch (_: Exception) {
                torn++
                return@forEach
            }
            val fields = json.getValue("fields").jsonObject
            if (json.getValue("tag").jsonPrimitive.content == "kern.logger") {
                if (gapLines.add(line)) gapCounts += fields.getValue("dropped").jsonPrimitive.long
            } else {
                val id = fields.getValue("thread").jsonPrimitive.int.toLong() shl 32 or
                    fields.getValue("seq").jsonPrimitive.long
                seen += id
            }
        }
        return Run(
            installed = installed,
            closed = closed,
            logged = threads * perThread.toLong(),
            distinct = seen.size.toLong(),
            dropped = installed.health.value.dropped,
            gapCounts = gapCounts,
            torn = torn,
        )
    }
}
