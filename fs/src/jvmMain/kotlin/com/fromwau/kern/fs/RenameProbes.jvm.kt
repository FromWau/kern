package com.fromwau.kern.fs

import com.sun.security.auth.module.UnixSystem
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.UserDefinedFileAttributeView

// A runtime without the jdk.security.auth module, or without UnixSystem's native half, cannot name the uid.
internal val currentUid: Int? by lazy {
    try {
        UnixSystem().uid.toInt()
    } catch (_: LinkageError) {
        null
    }
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
            carriesAttributes = carriesUserAttributes(file),
        )
    } catch (_: Exception) {
        null
    }
}

// Linux only: macOS lists its own attributes on many files. java.nio has no view of a POSIX ACL at all.
private val looksAtAttributes: Boolean = System.getProperty("os.name") == "Linux"

private fun userAttributes(file: java.nio.file.Path): UserDefinedFileAttributeView? =
    if (looksAtAttributes) Files.getFileAttributeView(file, UserDefinedFileAttributeView::class.java) else null

// A file system without attributes is not an identity that cannot be read.
private fun carriesUserAttributes(file: java.nio.file.Path): Boolean = try {
    userAttributes(file)?.list()?.isNotEmpty() == true
} catch (_: IOException) {
    false
}

internal actual fun copyAttributes(
    from: Path,
    to: Path,
): Boolean {
    val source = userAttributes(Paths.get(from.toString())) ?: return true
    val copy = userAttributes(Paths.get(to.toString())) ?: return true
    return try {
        val wanted = source.list()
        (copy.list() - wanted.toSet()).forEach(copy::delete)
        wanted.forEach { name ->
            val value = ByteBuffer.allocate(source.size(name))
            source.read(name, value)
            copy.write(name, value.flip())
        }
        true
    } catch (_: IOException) {
        false
    }
}
