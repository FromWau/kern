package com.fromwau.kern.terminal.test

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.ReadError
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * A [Terminal] for tests: it keeps what each stream was sent, hands [readIn] the [input] you give it, and fails a
 * stream's writes or the read when you tell it to.
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
 * Each parameter but [input] is what the property of the same name answers. Writes from several threads are kept in
 * the order they were made.
 *
 * @param input the bytes [readIn] reads, copied, so changing the array afterwards changes nothing.
 */
public class RecordingTerminal(
    override val columns: Int = 0,
    override val ansi: Boolean = false,
    override val errAnsi: Boolean = false,
    override val outIsTerminal: Boolean = false,
    override val errIsTerminal: Boolean = false,
    override val inIsTerminal: Boolean = false,
    input: ByteArray = ByteArray(0),
) : Terminal {
    private val lock = reentrantLock()
    private val recorded = mutableListOf<Write>()
    private val lost = mutableListOf<Write>()
    private val failures = mutableMapOf<Stream, WriteError>()
    private val input = input.copyOf()
    private var inputRead = false
    private var readFailure: ReadError? = null

    /** Everything written to standard output, except the writes that failed. */
    public val out: String
        get() = textOf(Stream.Out)

    /** Everything written to standard error, except the writes that failed. */
    public val err: String
        get() = textOf(Stream.Err)

    /**
     * Every write to either stream in the order it was made, except the writes that failed. Use it to pin the order
     * of standard output and standard error, as `2>&1` shows them.
     *
     * Each read is a copy: a later write does not change a list you already hold.
     */
    public val writes: List<Write>
        get() = lock.withLock { recorded.toList() }

    /**
     * Every write sent to a stream after [failWith] failed it, in the order made. Use it to pin what your code
     * tried to write to a broken stream, such as the message a CLI writes about the failure to the stream that failed.
     *
     * Each read is a copy, as with [writes].
     */
    public val lostWrites: List<Write>
        get() = lock.withLock { lost.toList() }

    override fun out(text: String): Unit = write(Stream.Out, text)

    override fun err(text: String): Unit = write(Stream.Err, text)

    /**
     * Fails every later write to [error]'s stream: those writes go to [lostWrites] instead of [writes], [out] or [err],
     * and [writeResult] reports [error] from now on. A later call for the same stream replaces it.
     */
    public fun failWith(error: WriteError) {
        lock.withLock { failures[error.stream] = error }
    }

    /** Fails every later [readIn] with [error]. A later call replaces it. */
    public fun failWith(error: ReadError) {
        lock.withLock { readFailure = error }
    }

    /**
     * The input this terminal was built with, the first time, and nothing after, as a real standard input reads only
     * to its end once. Past [maxBytes] it is [ReadError.TooLarge] one byte past the limit, as a real read answers, and
     * nothing is read. After [failWith] it is that error.
     */
    override fun readIn(maxBytes: Long?): Result<ByteArray, ReadError> = lock.withLock {
        val failure = readFailure
        val unread = if (inputRead) ByteArray(0) else input
        when {
            failure != null -> Err(failure)
            maxBytes != null && unread.size > maxBytes -> Err(ReadError.TooLarge(maxBytes, maxOf(maxBytes + 1, 0)))
            else -> {
                inputRead = true
                Ok(unread.copyOf())
            }
        }
    }

    override fun writeResult(): EmptyResult<WriteError> = lock.withLock { failures[Stream.Out] ?: failures[Stream.Err] }
        ?.let { Err(it) }
        ?: Ok(Unit)

    override fun writeResult(stream: Stream): EmptyResult<WriteError> = lock.withLock { failures[stream] }
        ?.let { Err(it) }
        ?: Ok(Unit)

    private fun textOf(stream: Stream): String = writes
        .filter { it.stream == stream }
        .joinToString("") { it.text }

    private fun write(stream: Stream, text: String) {
        lock.withLock {
            if (stream in failures) lost += Write(stream, text) else recorded += Write(stream, text)
        }
    }
}

/** One write a [RecordingTerminal] saw: the [text] sent to [stream]. */
public data class Write(val stream: Stream, val text: String)
