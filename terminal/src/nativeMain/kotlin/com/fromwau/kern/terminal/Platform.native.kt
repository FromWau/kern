package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.fileno
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.isatty
import platform.posix.stderr
import platform.posix.stdout

/**
 * Stdio shared by every native target; width and ANSI capability are the only per-family answers. No
 * [PlatformIo.writeFailed]: SIGPIPE kills the process on a closed pipe before anything can ask, and
 * Windows offers no equivalent to detect.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun nativePlatformIo(width: Int?, ansiCapable: Boolean): PlatformIo = PlatformIo(
    writeOut = { print(it) },
    writeErr = { fputs(it, stderr) },
    isTty = isatty(fileno(stdout)) != 0,
    width = width,
    ansiCapable = ansiCapable,
    env = { getenv(it)?.toKString() },
)
