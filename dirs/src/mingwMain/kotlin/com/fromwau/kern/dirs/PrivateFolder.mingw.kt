package com.fromwau.kern.dirs

import kotlinx.io.files.Path

/** False: Windows has no owner and mode bits that say only this user may enter a folder. */
internal actual fun isPrivateFolder(path: Path): Boolean = false
