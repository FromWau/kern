package com.fromwau.kern.fs

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Every entry below this folder by its path relative to it: `dir` for a folder, `-> target` for a symlink, never
 * followed, and a file's content, so a test can tell that a call touched nothing.
 */
internal fun Path.tree(): Map<String, String> {
    val found = mutableMapOf<String, String>()
    fun visit(
        folder: Path,
        prefix: String,
    ) {
        for (entry in SystemFileSystem.list(folder).sortedBy { it.name }) {
            val relative = prefix + entry.name
            when {
                isSymlink(entry) -> found[relative] = "-> ${readLink(entry)}"
                SystemFileSystem.metadataOrNull(entry)?.isDirectory == true -> {
                    found[relative] = "dir"
                    visit(entry, "$relative/")
                }
                else -> found[relative] = entry.readRaw()
            }
        }
    }
    visit(this, "")
    return found
}
