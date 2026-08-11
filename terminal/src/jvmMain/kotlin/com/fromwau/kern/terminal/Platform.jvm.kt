package com.fromwau.kern.terminal

import java.io.FileDescriptor
import java.io.FileOutputStream

// isTerminal() is documented as isatty on stdin AND stdout, so a piped stdin reads as "not a terminal"
// even when stdout is one, and the JDK exposes no stdout-only probe. --color and FORCE_COLOR/
// CLICOLOR_FORCE override auto detection and are unaffected.
internal actual fun platformIo(): PlatformIo = jvmPlatformIo(
    isTty = System.console()?.isTerminal == true,
    outSink = FileOutputStream(FileDescriptor.out),
    errSink = FileOutputStream(FileDescriptor.err),
)
