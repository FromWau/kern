package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.blue
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.brightBlack
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.kern.terminal.err
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.white
import com.fromwau.kern.terminal.withAnsi
import com.fromwau.kern.terminal.yellow
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/** One per stream, held for each whole line, so lines that several sinks write to one stream never interleave. */
private val streamLocks: Map<Stream, ReentrantLock> = Stream.entries.associateWith { reentrantLock() }

/**
 * Writes each entry as a line to [stream] of a terminal of its own, coloured for its severity when [color] allows it.
 * A [LogFormat.JSON] line is never coloured, so it stays one parseable object per line. A terminal keeps a stream's
 * first failed write and writes nothing after it, so [close] puts a new one from [newTerminal] in its place, and the
 * next entry after a close is written again. Every console writer of a stream takes that stream's one lock per line.
 */
internal class ConsoleWriter(
    private val stream: Stream,
    private val format: LogFormat,
    private val color: ColorMode,
    private val newTerminal: () -> Terminal = ::defaultTerminal,
) : EntryWriter {
    private val localClock by lazy(LazyThreadSafetyMode.PUBLICATION) { systemLocalClock() }

    // Made on the first write after construction or a close; a close never runs during a write.
    @Volatile
    private var opened: Opened? = null

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
        val (terminal, inColorMode) = opened ?: open().also { opened = it }
        val line = colorize(entry.render(format, localClock, config.timestamp), entry.level) + "\n"
        val failure = streamLocks.getValue(stream).withLock {
            if (stream == Stream.Out) inColorMode.out(line) else inColorMode.err(line)
            terminal.writeResult(stream).errorOrNull()
        }
        return if (failure != null) Err(SinkError.Dropped(failure)) else Ok(Unit)
    }

    override fun close(): EmptyResult<SinkError> {
        opened = null
        return Ok(Unit)
    }

    private fun open(): Opened {
        val terminal = newTerminal()
        val colours = terminal.withAnsi(
            out = colors(format, color, terminal.ansi),
            err = colors(format, color, terminal.errAnsi),
        )
        return Opened(terminal, colours)
    }

    /** A [terminal], and the same terminal colouring as [color] says, which is what lines are written through. */
    private data class Opened(
        val terminal: Terminal,
        val inColorMode: Terminal,
    )
}

/** The [PlatformSink] writer where the program's own terminal is the platform's log: coloured text on stdout. */
internal fun terminalWriter(): EntryWriter = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.AUTO)

/** Whether a console line is coloured, given what the stream's terminal would accept under `AUTO`. */
internal fun colors(format: LogFormat, mode: ColorMode, terminalTakesColor: Boolean): Boolean =
    format == LogFormat.TEXT && when (mode) {
        ColorMode.AUTO -> terminalTakesColor
        ColorMode.ALWAYS -> true
        ColorMode.NEVER -> false
    }

internal actual fun reportToStandardError(message: String) {
    streamLocks.getValue(Stream.Err).withLock { defaultTerminal().err("$message\n") }
}

/** The colour each severity prints in. */
private val LogLevel.style: Style
    get() = when (this) {
        LogLevel.VERBOSE -> brightBlack
        LogLevel.DEBUG -> blue
        LogLevel.INFO, LogLevel.OFF -> white
        LogLevel.WARN -> yellow
        LogLevel.ERROR -> red
        LogLevel.WTF -> bold + red
    }

/** [line] in the colour of its severity. */
internal fun colorize(line: String, level: LogLevel): Styled = level.style(line)
