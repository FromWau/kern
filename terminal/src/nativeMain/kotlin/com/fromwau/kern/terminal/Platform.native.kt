package com.fromwau.kern.terminal

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EOF
import platform.posix.FILE
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.stderr
import platform.posix.stdout

/**
 * Stdio shared by every native target; width and ANSI capability are the only per-family answers. A closed
 * pipe on POSIX ends the process with SIGPIPE before anything can ask, so [PlatformIo.writeFailed] reports
 * the failures that raise no signal: a full disk, a closed or unwritable handle.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nativePlatformIo(width: Int?, ansiCapable: Boolean): PlatformIo {
    val out = StdioSink(stdout)
    val err = StdioSink(stderr)
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = isatty(fileno(stdout)) != 0,
        width = width,
        ansiCapable = ansiCapable,
        env = { getenv(it)?.toKString() },
        writeFailed = { out.failed || err.failed },
    )
}

/** Writes to [stream] at once and remembers whether the system refused a write. */
@OptIn(ExperimentalForeignApi::class)
internal class StdioSink(private val stream: CPointer<FILE>?) {
    var failed: Boolean = false
        private set

    fun write(text: String) {
        // Flushed per write: text left in the buffer would fail only at exit, after the exit code was chosen.
        if (fputs(text, stream) == EOF || fflush(stream) != 0) failed = true
    }
}
