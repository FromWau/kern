package com.fromwau.kern.terminal

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
     * Whether a write has already failed, typically because a downstream `| head` closed the pipe. Ask
     * after writing and report [BROKEN_PIPE_EXIT] instead of a false success. The default `false` is right
     * for any terminal whose writes cannot fail quietly.
     */
    public fun writeErrored(): Boolean = false
}
