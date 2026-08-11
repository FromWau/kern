package com.fromwau.kern.terminal

import java.io.OutputStream
import java.io.PrintStream

/**
 * Shared JVM/Android stdio; the platform actual supplies the sinks and the [isTty] probe, which is all
 * that differs per platform (isTerminal() vs console presence).
 */
internal fun jvmPlatformIo(isTty: Boolean, outSink: OutputStream, errSink: OutputStream): PlatformIo {
    // A dedicated stream per call rather than System.out/System.err: checkError() latches on the first
    // failure and never resets, so a shared process-wide stream would report an older run's error here.
    val outStream = PrintStream(outSink, true)
    val errStream = PrintStream(errSink, true)
    return PlatformIo(
        writeOut = { outStream.print(it) },
        writeErr = { errStream.print(it) },
        isTty = isTty,
        // No ioctl without JNI, so the COLUMNS env var handled by resolveColumns is the only width source.
        width = null,
        ansiCapable = true,
        env = { System.getenv(it) },
        writeFailed = { outStream.checkError() || errStream.checkError() },
    )
}
