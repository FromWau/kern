package com.fromwau.kern.fs

import kotlinx.io.files.Path

// kotlinx-io throws here when a directory may not be read, so an empty listing is always a real one.
internal actual fun listDenial(path: Path): PathProbe.Denied? = null
