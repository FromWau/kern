package com.fromwau.kern.terminal

import java.io.Console
import java.io.FileDescriptor
import java.io.FileOutputStream

// Either probe answers for stdin AND stdout together, so a piped stdin reads as "not a terminal" even when stdout is
// one, and the JDK exposes no stdout-only probe. --color and FORCE_COLOR/CLICOLOR_FORCE override auto detection and
// are unaffected.
internal actual fun platformIo(): PlatformIo = jvmPlatformIo(
    isTty = isTerminal(),
    outSink = FileOutputStream(FileDescriptor.out),
    errSink = FileOutputStream(FileDescriptor.err),
)

// JDK 22 to 24, and any JDK run with -Djdk.console=jdk.internal.le, hand out a Console when a stream is redirected,
// and isTerminal(), added in 22, tells. Otherwise a Console exists only when both are terminals. Looked up by name,
// because the artifacts are built for JDK 17.
private fun isTerminal(): Boolean {
    val console = System.console() ?: return false
    val isTerminal = try {
        Console::class.java.getMethod("isTerminal")
    } catch (_: NoSuchMethodException) {
        return true
    }
    return isTerminal.invoke(console) as Boolean
}
