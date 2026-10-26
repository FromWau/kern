package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.openAppend
import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.InstalledSink
import com.fromwau.kern.logger.InternalKernApi
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.logger.reportFailuresTo
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

internal val entry = LogEntry(Instant.parse("2026-08-11T12:34:56.789Z"), "app", LogLevel.INFO, "hello")

// Linux native's SystemTemporaryDirectory is empty and relative when neither TMPDIR nor TMP is set.
private val tempBase: Path = SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")

/** A fresh, empty directory of its own under the system's temp directory. Remove it with [deleteTree]. */
internal fun newTempDir(): Path = Path(tempBase, "kern-logger-file-${Uuid.random()}").also {
    SystemFileSystem.createDirectories(it)
}

/** Deletes a tree of regular files and directories. */
internal fun Path.deleteTree() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        SystemFileSystem.list(this).forEach { it.deleteTree() }
    }
    SystemFileSystem.delete(this, mustExist = false)
}

internal fun Path.readRaw(): String = SystemFileSystem
    .source(this)
    .buffered()
    .use { it.readString() }

internal fun Path.lines(): List<String> = readRaw().lines().filter { it.isNotEmpty() }

/** Each line's message, the part after the tag. */
internal fun Path.messages(): List<String> = lines().map { it.substringAfter(" - ") }

/**
 * A logger with no sink, whose reports of its own failures are dropped rather than printed. Close it, rather than
 * removing its sinks, so nothing writes once a test has cleaned up.
 */
@OptIn(InternalKernApi::class)
internal fun testLogger(): Logger = Logger().also { logger ->
    logger.reportFailuresTo {}
    logger.configure { LoggerConfig(emptyList()) }.assertSuccess()
}

/** Adds [sink] with [config] at the end of the configured list and returns its handle. */
internal fun Logger.addOrFail(sink: LogSink, config: SinkConfig = SinkConfig()): InstalledSink {
    configure { it.copy(sinks = it.sinks + ConfiguredSink(sink, config)) }.assertSuccess()
    return sinks.value.single { it.sink === sink }
}

/** Changes the settings [sink] is paired with. */
internal fun Logger.configureOrFail(sink: LogSink, transform: (SinkConfig) -> SinkConfig) {
    configure { config ->
        config.copy(sinks = config.sinks.map { if (it.sink === sink) it.copy(config = transform(it.config)) else it })
    }.assertSuccess()
}

/** A [FileSink] with the clock and the open call it uses replaced. */
internal fun testFileSink(
    path: Path,
    capacity: Int = 10_000,
    onFailure: SinkFailure = SinkFailure.Retry(),
    timeSource: TimeSource = TimeSource.Monotonic,
    openFile: (Path) -> Result<AppendFile, FileError> = { it.openAppend() },
): FileSink = FileSink(path, onFailure = onFailure, capacity = capacity).also {
    it.timeSource = timeSource
    it.openFile = openFile
}

/** Polls [condition] in real time, which runTest's virtual clock would otherwise skip past. */
internal suspend fun eventually(timeout: Duration = 5.seconds, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeout) {
            while (!condition()) delay(5.milliseconds)
        }
    }
}

/** Waits [duration] of real time. */
internal suspend fun realDelay(duration: Duration) {
    withContext(Dispatchers.Default) { delay(duration) }
}

/** An exception whose every description throws, as a broken `toString()` override does. */
internal class HostileException : Exception() {
    override val message: String
        get() = throw IllegalStateException("message")

    override fun toString(): String = throw IllegalStateException("toString")
}
