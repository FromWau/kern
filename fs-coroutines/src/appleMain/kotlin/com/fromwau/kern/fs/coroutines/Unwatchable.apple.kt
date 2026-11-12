package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.PseudoFileSystem
import kotlinx.io.files.Path

// procfs and sysfs are Linux's alone.
internal actual fun pseudoFileSystemOf(path: Path): PseudoFileSystem? = null
