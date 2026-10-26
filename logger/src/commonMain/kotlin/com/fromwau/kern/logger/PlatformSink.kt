package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok

/**
 * The platform's own log: logcat on Android, the unified log (`os_log`) on iOS and in a macOS app, the console
 * method of each severity on JS and Wasm, and standard output, as coloured text, on the JVM, Linux, Windows and in
 * a macOS command-line tool. Logcat and the unified log carry their own timestamp and level, so they get the
 * message, fields and error alone. Every new [Logger], [Log] included, starts with one.
 */
public class PlatformSink(
    override val name: String = "platform",
    override val onFailure: SinkFailure = SinkFailure.Drop(),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
) : LogSink {
    private val writer = platformWriter()

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = writer.write(entry, config)

    override fun close(): EmptyResult<SinkError> = writer.close()
}

/** Writes to the platform's own log. */
internal expect fun platformWriter(): EntryWriter

/** How a built-in sink writes each entry, and lets go of what it holds when the sink is closed. */
internal fun interface EntryWriter {
    fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError>

    fun close(): EmptyResult<SinkError> = Ok(Unit)
}
