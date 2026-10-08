package com.fromwau.kern.terminal

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EPIPE
import platform.posix.FILE
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.posix_errno
import platform.posix.stderr
import platform.posix.stdout
import platform.posix.strerror

/**
 * Stdio shared by every native target; width and ANSI capability are the only per-family answers. A closed
 * pipe on POSIX ends the process with SIGPIPE before anything can ask, unless the program ignores that
 * signal; [PlatformIo.writeFailure] reports what remains: a full disk, a closed or unwritable handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nativePlatformIo(width: Int?, ansiCapable: Boolean): PlatformIo {
    val out = StdioSink(stdout, Stream.Out)
    val err = StdioSink(stderr, Stream.Err)
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = isatty(fileno(stdout)) != 0,
        errIsTty = isatty(fileno(stderr)) != 0,
        width = width,
        ansiCapable = ansiCapable,
        env = { getenv(it)?.toKString() },
        writeFailure = { out.failure ?: err.failure },
    )
}

/** Writes to [file] at once, keeps the first write the system refused, and writes nothing after it. */
@OptIn(ExperimentalForeignApi::class)
internal class StdioSink(
    private val file: CPointer<FILE>?,
    private val stream: Stream,
) {
    var failure: WriteError? = null
        private set

    fun write(text: String) {
        if (failure != null) return
        val bytes = text.encodeToByteArray()
        // Written by length rather than as a C string, which would end the text at its first NUL.
        val written = if (bytes.isEmpty()) {
            0
        } else {
            bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u, bytes.size.convert(), file).toInt() }
        }
        // Flushed per write: text left in the buffer would fail only at exit, after the exit code was chosen.
        if (written != bytes.size || fflush(file) != 0) failure = errorOf(posix_errno())
    }

    private fun errorOf(errno: Int): WriteError =
        if (errno == EPIPE) WriteError.BrokenPipe(stream) else WriteError.Refused(stream, strerror(errno)?.toKString())
}
