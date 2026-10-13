package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths

// The JVM has no getuid, and a file this process creates is owned by exactly the user it runs as.
private val currentUid: Int? by lazy {
    runCatching {
        val probe = Files.createTempFile("kern-uid", null)
        try {
            Files.getAttribute(probe, "unix:uid") as Int
        } finally {
            Files.deleteIfExists(probe)
        }
    }.getOrNull()
}

internal actual fun fileIdentity(path: Path): FileIdentity? {
    // Without the unix view (Windows) java.nio reads no link count and no uid, so both are unknown.
    if (!hasUnixView) return null
    val uid = currentUid ?: return null
    return try {
        val file = Paths.get(path.toString())
        FileIdentity(
            linkCount = (Files.getAttribute(file, "unix:nlink") as Int).toLong(),
            ownedByCurrentUser = Files.getAttribute(file, "unix:uid") == uid,
        )
    } catch (_: Exception) {
        null
    }
}
