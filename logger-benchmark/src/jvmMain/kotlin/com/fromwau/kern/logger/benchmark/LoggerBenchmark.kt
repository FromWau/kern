package com.fromwau.kern.logger.benchmark

import com.fromwau.kern.logger.ConsoleOutput
import com.fromwau.kern.logger.FileOutput
import com.fromwau.kern.logger.LogDelivery
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.TaggedLogger
import com.fromwau.kern.logger.async.AsyncDelivery
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads

/**
 * One log call to a file, as the thread that logs pays for it. With `async` that is only the hand-over to the
 * writer, whose queue drops what it cannot keep up with.
 */
@State(Scope.Benchmark)
class LoggerBenchmark {
    @Param("TEXT", "JSON")
    var format: String = "TEXT"

    @Param("inline", "async")
    var delivery: String = "inline"

    private lateinit var logger: Logger
    private lateinit var log: TaggedLogger

    // Under the build directory rather than the temp directory, which is often a RAM disk.
    private val file = Path("build", "benchmark", "bench.log")

    @Setup
    fun setUp() {
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
        val chosen = if (delivery == "async") AsyncDelivery() else LogDelivery.Inline
        logger = Logger(delivery = chosen)
        logger.configure(
            LoggerConfig(
                console = ConsoleOutput.Off,
                file = FileOutput.Append(file, format = LogFormat.valueOf(format)),
            ),
        )
        log = logger.tag("bench")
    }

    @TearDown
    fun tearDown() {
        logger.close()
        SystemFileSystem.delete(file)
    }

    @Benchmark
    fun oneThread() = log.i("count" to 412, "ms" to 1200L, "cached" to true) { "scan complete" }

    @Benchmark
    @Threads(4)
    fun fourThreads() = log.i("count" to 412, "ms" to 1200L, "cached" to true) { "scan complete" }

    @Benchmark
    fun filteredOut() = log.d { "below the configured level" }
}
