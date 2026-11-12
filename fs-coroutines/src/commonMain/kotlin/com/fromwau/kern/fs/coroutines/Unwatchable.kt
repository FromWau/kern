package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.PseudoFileSystem
import kotlinx.io.files.Path

/**
 * The pseudo file system [path] is on, judged by the nearest of it and its folders that exists, or null for any other
 * file system and where there is no telling.
 */
internal expect fun pseudoFileSystemOf(path: Path): PseudoFileSystem?
