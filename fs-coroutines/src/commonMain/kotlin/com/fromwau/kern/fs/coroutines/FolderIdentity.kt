package com.fromwau.kern.fs.coroutines

import kotlinx.io.files.Path

/**
 * What tells this folder apart from another later put at its path, such as its device and inode; null when it cannot
 * be looked up.
 */
internal expect fun Path.folderIdentity(): Any?
