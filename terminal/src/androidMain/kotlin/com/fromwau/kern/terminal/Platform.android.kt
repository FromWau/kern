package com.fromwau.kern.terminal

import java.io.FileDescriptor
import java.io.FileOutputStream

// Console.isTerminal() does not resolve on Android, so console presence is the only probe available:
// null whenever either standard stream is redirected, and in an app process.
internal actual fun platformIo(): PlatformIo = jvmPlatformIo(
    isTty = System.console() != null,
    outSink = FileOutputStream(FileDescriptor.out),
    errSink = FileOutputStream(FileDescriptor.err),
)
