package com.fromwau.kern.exec

private const val SIGNAL_EXIT_BASE = 128

/** The shell's exit code: [exitStatus] for a normal exit, 128 plus [signal] for a death by it. One is non-null. */
internal fun exitCodeOf(exitStatus: Int?, signal: Int?): Int =
    exitStatus ?: ((signal ?: 0) + SIGNAL_EXIT_BASE)
