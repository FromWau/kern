package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result

/** Exit code for a truncated output pipe: the shell's 128+N "killed by signal N" convention, N = SIGPIPE (13). */
public const val BROKEN_PIPE_EXIT: Int = 128 + 13

/**
 * Where text goes. [defaultTerminal] hands you the process's real stdout/stderr; implement this yourself to
 * capture output instead, which is how a test drives a program without touching the process streams:
 *
 * ```kotlin
 * class Recorder : Terminal {
 *     val written = StringBuilder()
 *     override fun out(text: String) { written.append(text) }
 *     override fun err(text: String) = Unit
 * }
 * ```
 *
 * [columns] and [ansi] default to "unknown, assume neither", which is the safe reading for a capture like
 * the one above: no wrapping and no escape codes unless something says otherwise.
 */
public interface Terminal {
    /** Writes [text] to standard output. No newline is added, so include one when you want one. */
    public fun out(text: String)

    /** Writes [text] to standard error. */
    public fun err(text: String)

    /** Usable width in columns, or 0 when unknown, which means "do not wrap". */
    public val columns: Int get() = 0

    /** Whether ANSI colour is appropriate for this terminal right now. */
    public val ansi: Boolean get() = false

    /**
     * Whether every write so far reached its destination, or why one did not: standard output's failure before
     * standard error's. A stream writes nothing after its first failure. Ask after writing instead of reporting a
     * false success. A [WriteError.BrokenPipe] is the reader's choice and is usually reported through
     * [BROKEN_PIPE_EXIT] alone; a [WriteError.Refused] lost output the user expected. Linux and macOS tell the two
     * apart; the JVM answers [WriteError.Unknown]. The default success is right for any terminal whose writes
     * cannot fail quietly.
     */
    public fun writeResult(): EmptyResult<WriteError> = Ok(Unit)

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
public sealed interface WriteError : IError {
    public val stream: Stream

    /** The reader went away, as when a downstream `| head` stops reading. */
    public data class BrokenPipe(override val stream: Stream) : WriteError

    /** The system refused the write, as on a full disk or a closed handle. [detail] is its own message. */
    public data class Refused(override val stream: Stream, val detail: String?) : WriteError

    /**
     * A write failed for a reason the platform does not report, as on the JVM, where a closed pipe and a full
     * disk raise the same exception. [detail] is the platform's message, for showing, not for deciding.
     */
    public data class Unknown(override val stream: Stream, val detail: String?) : WriteError
}
