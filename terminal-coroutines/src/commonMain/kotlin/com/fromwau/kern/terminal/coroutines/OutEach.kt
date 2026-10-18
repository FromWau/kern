package com.fromwau.kern.terminal.coroutines

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.InternalKernApi
import com.fromwau.kern.terminal.OutChunks
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile

/**
 * Writes [pieces] to standard output one after another, each as given with no newline added, and returns how the
 * writes went. It stops collecting [pieces] at the first failed write to standard output, so a long output ends soon
 * after the reader of a pipe goes away:
 *
 * ```kotlin
 * val written = terminal.outEach(lines.map { "$it\n" })
 * ```
 *
 * Pieces are joined into writes of about 64 K characters, and a longer piece is written alone, never split. [Styled]
 * pieces are coloured when [Terminal.ansi] is true. After each write it waits with [Terminal.awaitWriteResult], and
 * returns what that answers: success after the last piece, also when there are none, or a failure, standard output's
 * before standard error's. A failed standard error does not stop the output and is reported at the end. If [pieces]
 * or a write throws, the exception reaches you.
 */
@OptIn(InternalKernApi::class)
public suspend fun Terminal.outEach(pieces: Flow<CharSequence>): EmptyResult<WriteError> {
    val chunks = OutChunks(ansi)
    var written: EmptyResult<WriteError> = Ok(Unit)
    var outWritten = true
    pieces
        .takeWhile { piece ->
            chunks.add(piece)?.let { chunk ->
                out(chunk)
                written = awaitWriteResult()
                outWritten = writeResult(Stream.Out) is Result.Success
            }
            outWritten
        }
        .collect()
    if (!outWritten) return written
    chunks.rest()?.let { out(it) }
    return awaitWriteResult()
}
