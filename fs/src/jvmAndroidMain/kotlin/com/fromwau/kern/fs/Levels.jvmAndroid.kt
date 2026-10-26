package com.fromwau.kern.fs

import kotlinx.io.files.Path

// No call here opens a name inside an open folder, so a writer reaches each folder by its path.
internal actual fun openLevel(path: Path): Level = openPathLevel(path)
