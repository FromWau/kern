@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.stat

// The calls below act on a name in a folder held open as a descriptor. Each answers as the C call does: 0 or a new
// descriptor, or -1 with errno set. Linux makes them as system calls, since its Kotlin/Native headers declare none.

/** What a folder-relative call takes for "from the working directory", so a whole path can stand for a name. */
internal expect val WORKING_DIRECTORY: Int

/** `openat` of [name] in [folder] with [flags], a file it creates getting [mode]. */
internal expect fun openAt(
    folder: Int,
    name: String,
    flags: Int,
    mode: Int,
): Int

/** The status of [name] itself in [folder], a link at it never followed, read into [into]. */
internal expect fun statusAt(
    folder: Int,
    name: String,
    into: CPointer<stat>,
): Int

/** `mkdirat` of [name] in [folder] with [mode]. */
internal expect fun makeFolderAt(
    folder: Int,
    name: String,
    mode: Int,
): Int

/** `unlinkat` of [name] in [folder]: the empty folder there when [isFolder], otherwise the file or link. */
internal expect fun removeAt(
    folder: Int,
    name: String,
    isFolder: Boolean,
): Int

/** `renameat` of [from] in [fromFolder] to [to] in [toFolder], replacing a file or link there. */
internal expect fun renameAt(
    fromFolder: Int,
    from: String,
    toFolder: Int,
    to: String,
): Int

/** `symlinkat`: makes [name] in [folder] a link holding [target]. */
internal expect fun symlinkAt(
    target: String,
    folder: Int,
    name: String,
): Int

/** `readlinkat` of [name] in [folder] into [buffer] of [size] bytes, answering the length read or -1. */
internal expect fun readLinkAt(
    folder: Int,
    name: String,
    buffer: CPointer<ByteVar>,
    size: Int,
): Int

/** `fchownat` of [name] in [folder] to [group], its owner and a link at it left as they are. */
internal expect fun changeGroupAt(
    folder: Int,
    name: String,
    group: UInt,
): Int
