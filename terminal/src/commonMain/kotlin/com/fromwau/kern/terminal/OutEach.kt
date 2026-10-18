package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result

private const val CHUNK_LENGTH = 64 * 1024

/**
 * Writes [pieces] to standard output one after another, each as given with no newline added, and stops taking pieces
 * at the first failed write to standard output, so a long output ends soon after the reader of a pipe goes away:
 *
 * ```kotlin
 * terminal.outEach(lines.map { "$it\n" }) { written -> exit(exitCode(terminal, written)) }
 * ```
 *
 * Pieces are joined into writes of about 64 K characters, and a longer piece is written alone, never split. [Styled]
 * pieces are coloured when [Terminal.ansi] is true. After each write it waits as [Terminal.afterWrites] does, so make
 * it your last call: under Node [finished] runs later, from Node's event loop. A failed standard error does not stop
 * the output and is reported at the end.
 *
 * If [pieces] or a write throws, [finished] is not called: the exception reaches you as one thrown from an
 * [Terminal.afterWrites] block does.
 *
 * @param finished called once with what [Terminal.afterWrites] passes its block: success after the last piece, also
 *   when there are none, or a failure, standard output's before standard error's. It may end the process.
 */
@OptIn(InternalKernApi::class)
public fun Terminal.outEach(pieces: Sequence<CharSequence>, finished: (EmptyResult<WriteError>) -> Unit) {
    val chunks = OutChunks(ansi)
    val remaining = pieces.iterator()

    fun writeNextChunk() {
        val chunk = chunks.next(remaining)
        if (chunk != null) out(chunk)
        afterWrites { written ->
            if (chunk != null && writeResult(Stream.Out) is Result.Success) writeNextChunk() else finished(written)
        }
    }

    writeNextChunk()
}

/**
 * Joins the pieces an `outEach` writes into chunks of about 64 K characters, each one `out` call. A piece longer than
 * that is a chunk of its own, never split, and a [Styled] piece is rendered with [ansi].
 */
@InternalKernApi
public class OutChunks(private val ansi: Boolean) {
    private val pending = mutableListOf<String>()
    private var pendingLength = 0

    /** Adds [piece], and returns the chunk to write before it when it does not fit, or null. */
    public fun add(piece: CharSequence): String? {
        val text = if (piece is Styled) piece.render(ansi) else piece.toString()
        val full = if (pendingLength + text.length > CHUNK_LENGTH) rest() else null
        if (text.isNotEmpty()) {
            pending += text
            pendingLength += text.length
        }
        return full
    }

    /** The chunk still waiting after the last piece, or null when there is none. */
    public fun rest(): String? {
        if (pending.isEmpty()) return null
        // A lone piece goes out as it is, so a long one is not copied.
        val chunk = pending.singleOrNull() ?: pending.joinToString("")
        pending.clear()
        pendingLength = 0
        return chunk
    }

    internal fun next(pieces: Iterator<CharSequence>): String? {
        while (pieces.hasNext()) add(pieces.next())?.let { return it }
        return rest()
    }
}
