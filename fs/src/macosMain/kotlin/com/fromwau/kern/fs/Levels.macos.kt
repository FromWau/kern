package com.fromwau.kern.fs

import kotlinx.io.files.Path

internal actual fun openLevel(path: Path): Level = openPathLevel(path)
