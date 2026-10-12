package com.fromwau.kern.logger

import kotlinx.io.files.Path

// A browser has no file system to append to, so the file's failure policy decides what a configured file costs.
internal actual fun openAppendFile(path: Path): AppendFile =
    throw UnsupportedOperationException("file logging is not supported on JS and Wasm: $path")
