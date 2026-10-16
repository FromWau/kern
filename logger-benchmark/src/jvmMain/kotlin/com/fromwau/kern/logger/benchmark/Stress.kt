package com.fromwau.kern.logger.benchmark

import com.fromwau.kern.logger.CloseError
import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.file.FileSink
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes

/**
 * Many threads logging a million entries to one JSON file, then a check of what landed: every line whole, every
 * entry written or counted as dropped, and each thread's entries in the order it logged them.
 *
 * Run with `./gradlew :logger-benchmark:stress`, or `-Pentries=N` for another total.
 */
fun main(args: Array<String>) {
    val total = args.firstOrNull()?.toInt() ?: 1_000_000
    val file = Path("build", "stress", "stress.log")

    println("$total entries per run, JSON, to ${SystemFileSystem.resolve(Path("build"))}")
    println(
        "threads  queue             wall s   entries/s   p50 µs   p99 µs  p99.9 µs    max µs  " +
            "drain ms    dropped     lines  torn  out of order",
    )
    for (threads in listOf(1, 4, 16, 64, 256)) {
        for (queue in Queue.entries) {
            println(run(threads, total, queue, file))
        }
    }
}

private enum class Queue(val label: String) {
    DEFAULT("default, drops"),
    UNBOUNDED("all, no drops"),
}

private fun run(threads: Int, total: Int, queue: Queue, file: Path): String {
    if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    val perThread = total / threads
    val logged = perThread * threads

    val fileSink = if (queue == Queue.DEFAULT) {
        FileSink(file, LogFormat.JSON)
    } else {
        FileSink(file, LogFormat.JSON, capacity = logged)
    }
    val logger = Logger()
    logger.configure { LoggerConfig(listOf(ConfiguredSink(fileSink))) }.getOrElse { error("$it") }
    val sink = logger.sinks.value.single()
    val log = logger.tag("stress")

    val latencies = LongArray(logged)
    val start = CountDownLatch(1)
    val workers = List(threads) { index ->
        thread {
            start.await()
            val offset = index * perThread
            for (seq in 0 until perThread) {
                val before = System.nanoTime()
                log.i("thread" to index, "seq" to seq) { "stress" }
                latencies[offset + seq] = System.nanoTime() - before
            }
        }
    }

    val began = System.nanoTime()
    start.countDown()
    workers.forEach { it.join() }
    val wall = System.nanoTime() - began

    val closing = System.nanoTime()
    val closed = logger.close(10.minutes)
    val drain = System.nanoTime() - closing

    val check = verify(File(file.toString()))
    val dropped = sink.health.value.dropped
    logger.configure { LoggerConfig(emptyList()) }.getOrElse { error("$it") }
    latencies.sort()

    return "%7d  %-15s %8.2f %11.0f %8.1f %8.1f %9.1f %9.0f %9.0f %10d %9d %5d %13d".format(
        threads,
        queue.label,
        wall / 1e9,
        logged / (wall / 1e9),
        latencies.percentile(0.50) / 1e3,
        latencies.percentile(0.99) / 1e3,
        latencies.percentile(0.999) / 1e3,
        latencies.last() / 1e3,
        drain / 1e6,
        dropped,
        check.lines,
        check.torn,
        check.outOfOrder,
    ).also {
        // A close reports what the queue dropped as lost; anything else is a failure of the run.
        val lostAsCounted = CloseError(mapOf(sink.name to listOf(SinkError.Lost(dropped))))
        closed.getOrElse { if (it != lostAsCounted) System.err.println("  close: $it") }
        val missing = logged - dropped - check.lines
        if (missing != 0L) System.err.println("  $missing entries neither written nor counted as dropped")
    }
}

private class Check(val lines: Int, val torn: Int, val outOfOrder: Int)

private val THREAD_AND_SEQ = Regex(""""thread":(\d+),"seq":(\d+)""")

private fun verify(file: File): Check {
    var lines = 0
    var torn = 0
    var outOfOrder = 0
    val lastSeq = HashMap<Int, Int>()
    file.forEachLine { line ->
        // The line standing in for dropped entries is the logger's own, not one of the stress entries.
        if (line.contains("\"tag\":\"kern.logger\"")) return@forEachLine
        lines++
        val match = THREAD_AND_SEQ.find(line)
        if (!line.startsWith("{") || !line.endsWith("}") || match == null) {
            torn++
            return@forEachLine
        }
        val thread = match.groupValues[1].toInt()
        val seq = match.groupValues[2].toInt()
        if (seq <= (lastSeq[thread] ?: -1)) outOfOrder++
        lastSeq[thread] = seq
    }
    return Check(lines, torn, outOfOrder)
}

private fun LongArray.percentile(p: Double): Long = this[((size - 1) * p).toInt()]
