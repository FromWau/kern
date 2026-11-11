package com.fromwau.kern.fs

import kotlinx.io.files.Path

// macOS keeps an ACL outside its attributes and lists its own attributes on many files: unchecked until it can be run.
internal actual fun carriesAttributes(path: Path): Boolean = false

internal actual fun copyAttributes(
    from: Path,
    to: Path,
): Boolean = true
