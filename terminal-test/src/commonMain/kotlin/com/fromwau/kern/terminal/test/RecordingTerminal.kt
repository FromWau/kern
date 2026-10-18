package com.fromwau.kern.terminal.test

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A [Terminal] for tests: it keeps what each stream was sent, and fails a stream's writes when you tell it to.
 *
 * ```kotlin
 * val terminal = RecordingTerminal()
 * terminal.failWith(WriteError.BrokenPipe(Stream.Out))
 *
 * val exitCode = runCli(terminal, "--help")
 *
 * assertEquals(BROKEN_PIPE_EXIT, exitCode)
 * assertEquals("", terminal.out)
 * ```
 *
 * Each parameter is what the property of the same name answers. Writes from several threads are kept in the order
 * they were made.
 */
public class RecordingTerminal(
    override val columns: Int = 0,
    override val ansi: Boolean = false,
    override val errAnsi: Boolean = false,
    override val outIsTerminal: Boolean = false,
    override val errIsTerminal: Boolean = false,
) : Terminal {
    private val lock = reentrantLock()
    private val written = mapOf(Stream.Out to StringBuilder(), Stream.Err to StringBuilder())
    private val failures = mutableMapOf<Stream, WriteError>()

    /** Everything written to standard output, except the writes that failed. */
    public val out: String
        get() = lock.withLock { written.getValue(Stream.Out).toString() }

    /** Everything written to standard error, except the writes that failed. */
    public val err: String
        get() = lock.withLock { written.getValue(Stream.Err).toString() }

    override fun out(text: String): Unit = write(Stream.Out, text)

    override fun err(text: String): Unit = write(Stream.Err, text)

    /**
     * Fails every later write to [error]'s stream: nothing more is recorded for that stream, and [writeResult] reports
     * [error] from now on. A later call for the same stream replaces it.
     */
    public fun failWith(error: WriteError) {
        lock.withLock { failures[error.stream] = error }
    }

    override fun writeResult(): EmptyResult<WriteError> = lock.withLock { failures[Stream.Out] ?: failures[Stream.Err] }
        ?.let { Err(it) }
        ?: Ok(Unit)

    override fun writeResult(stream: Stream): EmptyResult<WriteError> = lock.withLock { failures[stream] }
        ?.let { Err(it) }
        ?: Ok(Unit)

    private fun write(stream: Stream, text: String) {
        lock.withLock {
            if (stream !in failures) written.getValue(stream).append(text)
        }
    }
}
