@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.io.files.Path
import platform.linux.SYS_getxattr
import platform.linux.SYS_listxattr
import platform.linux.SYS_removexattr
import platform.linux.SYS_setxattr
import platform.posix.ENOTSUP
import platform.posix.errno
import platform.posix.syscall

// Attributes that cannot be read count as there, the cautious answer.
internal actual fun carriesAttributes(path: Path): Boolean =
    attributeNames(path.toString())?.holdsMoreThanSecurityLabels() ?: true

/** Whether these attribute names hold one besides `security.*`, which SELinux puts on every file. */
internal fun List<String>.holdsMoreThanSecurityLabels(): Boolean = any { !it.startsWith("security.") }

internal actual fun copyAttributes(
    from: Path,
    to: Path,
): Boolean {
    val source = from.toString()
    val copy = to.toString()
    val wanted = attributeNames(source) ?: return false
    val there = attributeNames(copy) ?: return false
    // A folder's default ACL hands the new file one the old file may not have had.
    if ((there - wanted.toSet()).any { !removeAttribute(copy, it) }) return false
    return wanted.all { name ->
        val value = attributeValue(source, name) ?: return@all false
        // An SELinux label the copy already has may be one this user is not allowed to set.
        attributeValue(copy, name)?.contentEquals(value) == true || setAttribute(copy, name, value)
    }
}

/** The names of [path]'s attributes, none where its file system has none, or null when they cannot be read. */
private fun attributeNames(path: String): List<String>? = memScoped {
    val size = syscall(SYS_listxattr.toLong(), path.cstr.ptr, 0L, 0L)
    if (size < 0L) return@memScoped if (errno == ENOTSUP) emptyList() else null
    if (size == 0L) return@memScoped emptyList()
    val names = allocArray<ByteVar>(size)
    val read = syscall(SYS_listxattr.toLong(), path.cstr.ptr, names, size)
    if (read < 0L) return@memScoped null
    names
        .readBytes(read.toInt())
        .decodeToString()
        .split('\u0000')
        .filter { it.isNotEmpty() }
}

private fun attributeValue(
    path: String,
    name: String,
): ByteArray? = memScoped {
    val size = syscall(SYS_getxattr.toLong(), path.cstr.ptr, name.cstr.ptr, 0L, 0L)
    if (size < 0L) return@memScoped null
    if (size == 0L) return@memScoped ByteArray(0)
    val value = allocArray<ByteVar>(size)
    val read = syscall(SYS_getxattr.toLong(), path.cstr.ptr, name.cstr.ptr, value, size)
    if (read < 0L) null else value.readBytes(read.toInt())
}

private fun setAttribute(
    path: String,
    name: String,
    value: ByteArray,
): Boolean = memScoped {
    val bytes = allocArray<ByteVar>(value.size.coerceAtLeast(1))
    value.forEachIndexed { index, byte -> bytes[index] = byte }
    syscall(SYS_setxattr.toLong(), path.cstr.ptr, name.cstr.ptr, bytes, value.size.toLong(), 0L) == 0L
}

private fun removeAttribute(
    path: String,
    name: String,
): Boolean = memScoped { syscall(SYS_removexattr.toLong(), path.cstr.ptr, name.cstr.ptr) == 0L }
