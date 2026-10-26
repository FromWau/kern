package com.fromwau.kern.fs.test

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()

// Linux's is empty and relative when neither TMPDIR nor TMP is set.
internal actual fun platformTempRoot(): Path = SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")
