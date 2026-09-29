package com.fromwau.kern.dirs

import kotlinx.io.files.Path

// kotlinx-io's native removal already carries the OS's own words.
internal actual fun whyNotDeleted(path: Path, failure: Exception): String? = failure.reason

// Unreached in practice: kotlinx-io's native creation throws with the OS's words, which are used instead.
internal actual fun whyNotCreated(path: Path): String? = "Directory was not created"
