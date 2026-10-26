package com.fromwau.kern.fs

import kotlinx.io.files.Path

// java.nio has no move that refuses an existing folder in one step: its atomic move replaces an empty one.
internal actual fun renameNoReplace(
    from: Path,
    to: Path,
): NoReplaceRename = NoReplaceRename.Unavailable
