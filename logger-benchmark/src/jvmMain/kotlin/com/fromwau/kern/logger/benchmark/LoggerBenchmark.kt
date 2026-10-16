package com.fromwau.kern.logger.benchmark

import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.FlushError
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.TaggedLogger
import com.fromwau.kern.logger.file.FileSink
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import kotlin.time.Duration.Companion.minutes

private const val BATCH = 10_000

// Under the build directory rather than the temp directory, which is often a RAM disk.
private val file = Path("build", "benchmark", "bench.log")

/** A logger with only the sink [kind] names: `noop`, `file-text` or `file-json`; [capacity] null for the default. */
private fun loggerWith(kind: String, capacity: Int? = null): Logger {
    if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    fun fileSink(format: LogFormat) =
        if (capacity == null) FileSink(file, format) else FileSink(file, format, capacity = capacity)
    val sink = when (kind) {
        "noop" -> if (capacity == null) {
            LogSink("noop") { _, _ -> Ok(Unit) }
        } else {
            LogSink("noop", capacity = capacity) { _, _ -> Ok(Unit) }
        }
        "file-text" -> fileSink(LogFormat.TEXT)
        "file-json" -> fileSink(LogFormat.JSON)
        else -> error("unknown sink $kind")
    }
    return Logger().apply {
        configure { LoggerConfig(listOf(ConfiguredSink(sink))) }.getOrElse { error("$it") }
    }
}

/** Closes the logger; a sink that could not keep up only drops, which is reported as lost and printed here. */
private fun Logger.shutDown() {
    close().getOrElse { failed ->
        val errors = failed.errors.values.flatten()
        if (errors.any { it !is SinkError.Lost }) error("$failed")
        println("dropped ${errors.sumOf { (it as SinkError.Lost).count }} entries the sink could not keep up with")
    }
    configure { LoggerConfig(emptyList()) }.getOrElse { error("$it") }
    if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
}

/**
 * What one log call costs the thread that logs: the level check, building the entry, and queueing it for the sink.
 * The sink's worker writes in the background, and drops what it cannot keep up with, so the file sinks' scores
 * measure a saturated queue that drops, with the sink's default capacity.
 */
@State(Scope.Benchmark)
class LogCallBenchmark {
    @Param("noop", "file-text", "file-json")
    var sink: String = "noop"

    private lateinit var logger: Logger
    private lateinit var log: TaggedLogger

    @Setup
    fun setUp() {
        logger = loggerWith(sink)
        log = logger.tag("bench")
    }

    @TearDown
    fun tearDown() = logger.shutDown()

    @Benchmark
    fun logCall() = log.i("count" to 412, "ms" to 1200L, "cached" to true) { "scan complete" }

    @Benchmark
    @Threads(4)
    fun logCallFourThreads() = log.i("count" to 412, "ms" to 1200L, "cached" to true) { "scan complete" }

    /** An entry below every sink's level: returns before the message is built. */
    @Benchmark
    fun filteredCall() = log.d { "below the sink's level" }
}

/** How fast each sink's worker writes: per entry, a batch logged and then flushed, with room to drop nothing. */
@State(Scope.Benchmark)
class DrainBenchmark {
    @Param("noop", "file-text", "file-json")
    var sink: String = "noop"

    private lateinit var logger: Logger
    private lateinit var log: TaggedLogger

    @Setup
    fun setUp() {
        logger = loggerWith(sink, capacity = BATCH)
        log = logger.tag("bench")
    }

    @TearDown
    fun tearDown() = logger.shutDown()

    @Benchmark
    @OperationsPerInvocation(BATCH)
    fun drain(): Result<Flushed, FlushError> {
        repeat(BATCH) { log.i("count" to it, "cached" to true) { "scan complete" } }
        return logger.flush(1.minutes)
    }
}
