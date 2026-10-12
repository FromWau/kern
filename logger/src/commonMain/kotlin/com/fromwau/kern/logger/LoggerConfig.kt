package com.fromwau.kern.logger

/**
 * Everything the logger consults on its way to writing a line: its level, destinations and what to do
 * when one of them fails. It is live, so a change reaches the very next entry, and a new [Logger] starts with
 * the defaults below.
 *
 * Hand a new one to [Logger.configure], or change one setting with `configure { it.copy(...) }`. There is no
 * re-initialization step and nothing to restart: a settings screen that flips [level] to [LogLevel.DEBUG] gets
 * debug output immediately, and one that moves [file] gets the next line in the new file.
 *
 * ```kotlin
 * Log.configure(
 *     LoggerConfig(
 *         console = ConsoleOutput.Stderr(),
 *         file = FileOutput.Append(Path(stateDir, "app.log"), format = LogFormat.JSON),
 *     ),
 * )
 * ```
 *
 * @param level the threshold. An entry below it is dropped before its message is ever built, so an expensive
 *   `{ }` block costs nothing while it is filtered out.
 * @param console where each line is shown.
 * @param file the file each line is appended to, if any, and what happens when writing it fails.
 * @param sinkFailure what happens when a [LogSink] throws.
 */
public data class LoggerConfig(
    val level: LogLevel = LogLevel.INFO,
    val console: ConsoleOutput = ConsoleOutput.Platform(),
    val file: FileOutput = FileOutput.None,
    val sinkFailure: SinkFailure = SinkFailure.Drop(),
)

/** Where [LoggerConfig.console] shows each line. */
public sealed interface ConsoleOutput {
    /**
     * The platform's own log: logcat on Android, the unified log (`os_log`) on iOS and in a macOS app, the
     * console of the severity on JS and Wasm, and standard output on the JVM, Linux, Windows and in a macOS
     * command-line tool. [color] and [format] apply only where the line goes to a stream or a JS console: logcat
     * and the unified log carry their own timestamp and level, so they get the message and fields alone.
     */
    public data class Platform(
        val color: ColorMode = ColorMode.AUTO,
        val format: LogFormat = LogFormat.TEXT,
    ) : ConsoleOutput

    /** Standard output on every platform. */
    public data class Stdout(
        val color: ColorMode = ColorMode.AUTO,
        val format: LogFormat = LogFormat.TEXT,
    ) : ConsoleOutput

    /** Standard error on every platform, which keeps standard output for a CLI's data: `app | jq` reads only that. */
    public data class Stderr(
        val color: ColorMode = ColorMode.AUTO,
        val format: LogFormat = LogFormat.TEXT,
    ) : ConsoleOutput

    /** No console line at all. */
    public data object Off : ConsoleOutput
}

/** The format of a console line, or null with [ConsoleOutput.Off]. */
internal val ConsoleOutput.format: LogFormat?
    get() = when (this) {
        ConsoleOutput.Off -> null
        is ConsoleOutput.Platform -> format
        is ConsoleOutput.Stdout -> format
        is ConsoleOutput.Stderr -> format
    }

/** Whether a console line is wrapped in ANSI colour. A [LogFormat.JSON] line and the log file never are. */
public enum class ColorMode {
    /**
     * Colour only when the stream is a terminal, and never under `NO_COLOR` or `TERM=dumb`. `FORCE_COLOR=1`
     * colours a pipe anyway.
     */
    AUTO,

    /** Colour even when the stream is a pipe or a file, as CI logs usually want. */
    ALWAYS,

    NEVER,
}
