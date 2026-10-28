package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EISDIR
import platform.posix.EPIPE
import platform.posix.EWOULDBLOCK
import platform.posix.FILE
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.posix_errno
import platform.posix.read
import platform.posix.stderr
import platform.posix.stdout
import platform.posix.strerror
import platform.posix.usleep
import kotlin.concurrent.Volatile

/**
 * Stdio shared by every native target; width and each stream's ANSI capability are the only per-family answers. A
 * closed pipe on POSIX ends the process with SIGPIPE before anything can ask, unless the program ignores that
 * signal; [PlatformIo.failureOf] reports what remains: a full disk, a closed or unwritable handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nativePlatformIo(
    width: Int?,
    ansiCapable: Boolean,
    errAnsiCapable: Boolean = ansiCapable,
    readInChunk: (buffer: ByteArray) -> Result<Int, ReadError> = { readChunk(STDIN, it) },
): PlatformIo {
    val out = StdioSink(stdout, Stream.Out)
    val err = StdioSink(stderr, Stream.Err)
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = isatty(fileno(stdout)) != 0,
        errIsTty = isatty(fileno(stderr)) != 0,
        width = width,
        ansiCapable = ansiCapable,
        errAnsiCapable = errAnsiCapable,
        env = { getenv(it)?.toKString() },
        failureOf = { if (it == Stream.Out) out.failure else err.failure },
        inIsTty = isatty(STDIN) != 0,
        readInChunk = readInChunk,
    )
}

internal const val STDIN = 0

private const val NOTHING_YET_WAIT_MICROS = 10_000u

/** Reads the next bytes of [fd] into [buffer]: how many, 0 at its end, or why the read failed. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal fun readChunk(fd: Int, buffer: ByteArray): Result<Int, ReadError> {
    while (true) {
        val count = buffer.usePinned { pinned -> read(fd, pinned.addressOf(0), buffer.size.convert()) }.convert<Int>()
        if (count >= 0) return Ok(count)
        val errno = posix_errno()
        // A signal that arrived before any byte did; nothing was read, so asking again loses nothing.
        if (errno == EINTR) continue
        // A descriptor someone made non-blocking, such as a parent sharing it, has nothing yet: wait as Node does.
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            usleep(NOTHING_YET_WAIT_MICROS)
            continue
        }
        return Err(if (errno == EISDIR) ReadError.IsADirectory else ReadError.Refused(strerror(errno)?.toKString()))
    }
}

/** Writes to [file] at once, keeps the first write the system refused, and writes nothing after it. */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal class StdioSink(
    private val file: CPointer<FILE>?,
    private val stream: Stream,
) {
    // Read by whichever thread asks failureOf, not only the one writing.
    @Volatile
    var failure: WriteError? = null
        private set

    fun write(text: String) {
        if (failure != null) return
        val bytes = text.encodeToByteArray()
        // Written by length rather than as a C string, which would end the text at its first NUL.
        val written = if (bytes.isEmpty()) {
            0
        } else {
            // size_t is 32 bits wide on the 32-bit Android targets and 64 on the rest; convert() bridges both.
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).convert<Int>()
            }
        }
        // Flushed per write: text left in the buffer would fail only at exit, after the exit code was chosen.
        if (written != bytes.size || fflush(file) != 0) failure = errorOf(posix_errno())
    }

    private fun errorOf(errno: Int): WriteError =
        if (errno == EPIPE) WriteError.BrokenPipe(stream) else WriteError.Refused(stream, strerror(errno)?.toKString())
}

@kotlin.native.concurrent.ThreadLocal
private val queue = AfterWritesQueue()

internal actual fun afterWritesQueue(): AfterWritesQueue = queue
