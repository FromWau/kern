package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.errorOrNull

/** Exit code for a truncated output pipe: the shell's 128+N "killed by signal N" convention, N = SIGPIPE (13). */
public const val BROKEN_PIPE_EXIT: Int = 128 + 13

/**
 * Where text goes. [defaultTerminal] hands you the process's real stdout/stderr. In a test, use `RecordingTerminal`
 * from `kern:terminal-test`, or implement this yourself:
 *
 * ```kotlin
 * class Recorder : Terminal {
 *     val written = StringBuilder()
 *     override fun out(text: String) { written.append(text) }
 *     override fun err(text: String) = Unit
 * }
 * ```
 *
 * A terminal of your own that overrides only [out] and [err] has a [columns] of 0, [ansi], [errAnsi], [outIsTerminal],
 * [errIsTerminal] and [inIsTerminal] false, every write succeeds and [readIn] reads nothing: no wrapping and no escape
 * codes unless something says otherwise. To report failures, override [writeResult]; the per-stream [writeResult],
 * [afterWrites] and [awaitWriteResult] read it.
 */
public interface Terminal {
    /** Writes [text] to standard output. No newline is added, so include one when you want one. */
    public fun out(text: String)

    /** Writes [text] to standard error. */
    public fun err(text: String)

    /** Usable width in columns, or 0 when unknown, which means "do not wrap". */
    public val columns: Int get() = 0

    /** Whether ANSI colour is appropriate for standard output right now. */
    public val ansi: Boolean get() = false

    /**
     * Whether ANSI colour is appropriate for standard error right now, decided like [ansi] but for that stream,
     * so `2>file` stays plain while standard output is a terminal. The JVM and Android cannot ask whether standard
     * error is a terminal, so there it is true only when `FORCE_COLOR` or `CLICOLOR_FORCE` asks for colour.
     */
    public val errAnsi: Boolean get() = false

    /**
     * Whether standard output is a terminal, from the probe that decides [ansi]. Use it for layout, as in
     * `if (terminal.outIsTerminal) terminal.columns else 0`: [columns] falls back to standard error's width when
     * standard output is piped, so it is no evidence that standard output is a terminal. On the JVM and Android the
     * only probe answers for standard input and output together, so a piped standard input makes it false.
     */
    public val outIsTerminal: Boolean get() = false

    /**
     * Whether standard error is a terminal, from the probe that decides [errAnsi]. The JVM and Android cannot ask, so
     * there it is always false.
     */
    public val errIsTerminal: Boolean get() = false

    /**
     * Whether standard input is a terminal: true when a person may be typing, false for a pipe, a file or nothing at
     * all. Use it to skip a prompt or to refuse to wait for input nobody will type. On the JVM and Android the only
     * probe answers for standard input and output together, so a redirected standard output makes it false. A browser
     * has no standard input, so there it is false.
     */
    public val inIsTerminal: Boolean get() = false

    /**
     * Every byte of standard input up to its end, exactly as it was written: no newline or encoding translation, so a
     * CRLF, a byte order mark or bytes that are no valid UTF-8 come through as they are. It blocks until the writer
     * closes its end, or, at a terminal, until the user ends the input, as Ctrl-D does on Linux and macOS.
     * Read it once: the first call uses it up, so a second finds nothing more in a pipe or a file, and at a terminal
     * waits for the user to type again.
     *
     * ```kotlin
     * val text = terminal.readIn(maxBytes = 1_048_576).getOrElse { return fail(it) }.decodeToString()
     * ```
     *
     * It reads whatever standard input is, a pipe, a file, a socket or a device, so give [maxBytes] when it may never
     * end, as `/dev/zero` does. The default answers an empty input, as a process whose standard input is `/dev/null`
     * reads.
     *
     * @param maxBytes the most bytes read; once more than that arrives, the read stops with [ReadError.TooLarge].
     *   Null reads it all, however much that is.
     * @return the bytes, or why they could not be read.
     */
    public fun readIn(maxBytes: Long? = null): Result<ByteArray, ReadError> = Ok(ByteArray(0))

    /**
     * Whether every write so far reached its destination, or why one did not: standard output's failure before
     * standard error's. A stream writes nothing after its first failure. Ask after writing instead of reporting a
     * false success. A [WriteError.BrokenPipe] is the reader's choice and is usually reported through
     * [BROKEN_PIPE_EXIT] alone; a [WriteError.Refused] lost output the user expected. Linux, macOS and WASI tell
     * the two apart, and the JVM answers [WriteError.Unknown] for both. Node reports a failure only after the write
     * returns, so there ask [afterWrites] or [awaitWriteResult] instead. The default success is right for any
     * terminal whose writes cannot fail quietly; a terminal that can fail overrides this one, and the per-stream
     * [writeResult] reads it.
     */
    public fun writeResult(): EmptyResult<WriteError> = Ok(Unit)

    /**
     * [writeResult] for [stream] alone: a failure on the other stream neither hides this one's nor stands in for it.
     * The default reads [writeResult], so a terminal that only overrides that one reports at most the stream it names.
     */
    public fun writeResult(stream: Stream): EmptyResult<WriteError> {
        val failure = writeResult().errorOrNull()
        return if (failure != null && failure.stream == stream) Err(failure) else Ok(Unit)
    }

    /**
     * [writeResult], once every write so far has landed or failed. Under Node a failed write is reported after it
     * returns, so [writeResult] right after writing answers success, and this waits for Node's answer instead: a
     * closed pipe is [WriteError.BrokenPipe] and any other failure [WriteError.Unknown]. Everywhere else a write has
     * landed or failed when it returns, so this answers at once. Code that does not suspend calls [afterWrites].
     */
    public suspend fun awaitWriteResult(): EmptyResult<WriteError> = writeResult()

    /**
     * Calls [block] once with [writeResult], once every write so far has landed or failed: the form of
     * [awaitWriteResult] for code that does not suspend, such as a Kotlin/Native `main`.
     *
     * ```kotlin
     * terminal.afterWrites { written -> exitProcess(if (written is Result.Success) 0 else 1) }
     * ```
     *
     * Make it your last call. On the JVM, Android, Kotlin/Native and WASI [block] runs before this returns, so code
     * after the call runs after [block]. Under Node [block] runs later, from Node's event loop, once Node has answered
     * every write made before this call, so code after the call runs first. Those writes keep the process alive until
     * then, and nothing is left to write when [block] ends the process.
     *
     * A call from inside a block never runs inside it: its block runs once the running block returns. A loop that
     * writes a chunk and calls this again from its block therefore keeps a flat stack however many chunks it writes.
     * What a block throws reaches the outermost call, or is uncaught under Node, and the blocks still waiting on that
     * thread are dropped.
     */
    public fun afterWrites(block: (EmptyResult<WriteError>) -> Unit): Unit =
        afterWritesQueue().runOrQueue { block(writeResult()) }

    /** Whether a write has already failed. */
    @Deprecated(
        "Use writeResult(), which says why a write failed.",
        ReplaceWith("writeResult() is Result.Error", "com.fromwau.kern.result.Result"),
    )
    public fun writeErrored(): Boolean = writeResult() is Result.Error
}

/** The stream a [Terminal] writes to. */
public enum class Stream {
    /** Standard output, written by [Terminal.out]. */
    Out,

    /** Standard error, written by [Terminal.err]. */
    Err,
}

/** Why output did not reach its destination, and on which [stream]. */
public sealed interface WriteError : ResultError {
    /** The stream whose write failed. */
    public val stream: Stream

    /** The reader went away, as when a downstream `| head` stops reading. */
    public data class BrokenPipe(override val stream: Stream) : WriteError

    /** The system refused the write, as on a full disk or a closed handle. [detail] is its own message. */
    public data class Refused(override val stream: Stream, val detail: String?) : WriteError

    /**
     * A write failed for a reason the platform does not report. The JVM and Android raise the same exception for a
     * closed pipe and a full disk, so there `app | head -1` ends up here too. [detail] is the platform's message, for
     * showing, not for deciding, and in the system's language. [cause] is the exception the JDK threw, for you to
     * inspect or log; it is null elsewhere.
     */
    public data class Unknown(
        override val stream: Stream,
        val detail: String?,
        val cause: Throwable? = null,
    ) : WriteError
}

/** Why standard input could not be read. */
public sealed interface ReadError : ResultError {
    /**
     * Standard input is a directory, as with `app < /tmp`. Kotlin/Native, Node and WASI report it where the system
     * does, as Linux does; the JVM and Android answer [Unknown].
     */
    public data object IsADirectory : ReadError

    /**
     * Standard input holds at least [atLeastBytes] bytes, more than the caller's limit of [limitBytes]. It is a lower
     * bound, one byte past the limit: the read stops there instead of reading the rest.
     */
    public data class TooLarge(
        val limitBytes: Long,
        val atLeastBytes: Long,
    ) : ReadError

    /** This process has no standard input to read, as in a browser. */
    public data object Unavailable : ReadError

    /** The system refused the read, as a failing device does. [detail] is its own message. */
    public data class Refused(val detail: String?) : ReadError

    /**
     * A read failed for a reason the platform does not report: the JVM and Android raise every failure as the same
     * exception, and Node gives only its message. [detail] is the platform's message, for showing, not for deciding.
     * [cause] is the exception the JDK threw, for you to inspect or log; it is null elsewhere.
     */
    public data class Unknown(
        val detail: String?,
        val cause: Throwable? = null,
    ) : ReadError
}

/**
 * This terminal with its colour decision replaced: [Terminal.ansi] is [out] and [Terminal.errAnsi] is [err], and
 * everything else is answered by this terminal. For an override such as a `--color=always|never` option:
 *
 * ```kotlin
 * val terminal = defaultTerminal().withAnsi(out = true, err = true)
 * ```
 */
public fun Terminal.withAnsi(out: Boolean, err: Boolean): Terminal = AnsiOverride(this, out, err)

private class AnsiOverride(
    terminal: Terminal,
    override val ansi: Boolean,
    override val errAnsi: Boolean,
) : Terminal by terminal
