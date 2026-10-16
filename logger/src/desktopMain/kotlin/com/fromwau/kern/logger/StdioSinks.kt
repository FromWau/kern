package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.terminal.Stream

/**
 * Writes each entry to standard output. On JVM, Linux, Windows and macOS only; Android and iOS have no reader for
 * it, so use [PlatformSink] there.
 *
 * @param color whether a [LogFormat.TEXT] line is coloured for its severity.
 */
public class StdoutSink(
    public val format: LogFormat = LogFormat.TEXT,
    public val color: ColorMode = ColorMode.AUTO,
    override val name: String = "stdout",
    override val onFailure: SinkFailure = SinkFailure.Drop(),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
) : LogSink {
    private val writer = streamWriter(Stream.Out, format, color)

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = writer(entry, config)
}

/**
 * Writes each entry to standard error, which keeps standard output for a CLI's data: `app | jq` reads only that.
 * On JVM, Linux, Windows and macOS only.
 *
 * @param color whether a [LogFormat.TEXT] line is coloured for its severity.
 */
public class StderrSink(
    public val format: LogFormat = LogFormat.TEXT,
    public val color: ColorMode = ColorMode.AUTO,
    override val name: String = "stderr",
    override val onFailure: SinkFailure = SinkFailure.Drop(),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
) : LogSink {
    private val writer = streamWriter(Stream.Err, format, color)

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = writer(entry, config)
}
