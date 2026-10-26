@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.EEXIST
import platform.posix.ELOOP
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.O_CLOEXEC
import platform.posix.O_DIRECTORY
import platform.posix.O_NOFOLLOW
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.closedir
import platform.posix.dup
import platform.posix.errno
import platform.posix.fdopendir
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.readdir
import platform.posix.rewinddir
import platform.posix.set_posix_errno
import platform.posix.stat

/** [openLevel] for Linux and iOS: opened name by name with `O_NOFOLLOW` from the root, so no link is on the way. */
internal fun openDescriptorLevel(path: Path): Level {
    var current = openAt(WORKING_DIRECTORY, "/", O_RDONLY or O_DIRECTORY or O_CLOEXEC, 0)
    if (current < 0) throw errnoFailure(about = path)
    for (name in path.names()) {
        val next = openAt(current, name, FOLDER_OPEN, 0)
        val moved = next < 0 && errno in MOVED_ERRNOS
        val failure = if (next < 0) errnoFailure(about = path) else null
        close(current)
        if (moved) throw LevelMoved(path)
        if (failure != null) throw failure
        current = next
    }
    return DescriptorLevel(current, path)
}

/** How a folder below an open one is opened: never through a link, and never handed to a child process. */
private val FOLDER_OPEN = O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC

/** What an open of a folder answers once the name holds no folder, or a link: it was moved or replaced. */
private val MOVED_ERRNOS = setOf(ENOENT, ELOOP, ENOTDIR)

/** A [Level] held open as [descriptor], every name opened in it relative to it, never through a link. */
private class DescriptorLevel(
    private val descriptor: Int,
    override val path: Path,
) : Level {
    override fun pathOf(name: String): Path = Path(path, name)

    private fun opening(name: String): Opening = Opening(pathOf(name), descriptor, name, followLinks = false)

    override fun openSource(
        name: String,
        streams: Boolean,
    ): FileSource = openSourceAt(opening(name), streams)

    override fun openSink(
        name: String,
        append: Boolean,
        permissions: Int?,
        newMode: Int,
    ): FileSink = openSinkAt(opening(name), append, permissions, newMode)

    override fun createHeldFile(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): HeldFile = createHeldFileAt(opening(name), permissions, newMode)

    override fun permissionBits(name: String): Int = ownStatus(name) { it.st_mode.toInt() and PERMISSION_BITS }

    override fun copyGroup(
        from: String,
        to: String,
    ): Boolean = memScoped {
        val source = alloc<stat>()
        val copy = alloc<stat>()
        val looked = statusAt(descriptor, from, source.ptr) == 0 && statusAt(descriptor, to, copy.ptr) == 0
        if (!looked) return@memScoped false
        // Asked only when the groups differ: a file system without groups, such as FAT, refuses any chown.
        source.st_gid == copy.st_gid || changeGroupAt(descriptor, to, source.st_gid) == 0
    }

    override fun backUp(
        from: String,
        to: String,
        ownerOnly: Boolean,
    ): HeldFile = copyFile(from, to, ownerOnly)

    override fun rename(
        from: String,
        to: String,
    ) {
        if (renameAt(descriptor, from, descriptor, to) != 0) throw errnoFailure(about = pathOf(from))
    }

    override fun delete(name: String) {
        if (removeAt(descriptor, name, isFolder = false) != 0 && errno != ENOENT) {
            throw errnoFailure(about = pathOf(name))
        }
    }

    override fun isGone(name: String): Boolean = memScoped {
        statusAt(descriptor, name, alloc<stat>().ptr) != 0 && errno == ENOENT
    }

    override fun sync(): Exception? = if (fsync(descriptor) == 0) null else errnoFailure(about = path)

    override fun info(): FileInfo = fileInfoOf(descriptor, path)

    override fun key(): Any = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) throw errnoFailure(about = path)
        info.key()
    }

    override fun typeOf(name: String): FileType? = memScoped {
        val info = alloc<stat>()
        if (statusAt(descriptor, name, info.ptr) == 0) return@memScoped fileTypeOfMode(info.st_mode.toInt())
        if (errno == ENOENT) null else throw errnoFailure(about = pathOf(name))
    }

    override fun infoOf(name: String): FileInfo = ownStatus(name) { it.toFileInfo() }

    /** What [read] takes from the status of [name] itself, throwing why it could not be looked up. */
    private fun <T> ownStatus(
        name: String,
        read: (stat) -> T,
    ): T = memScoped {
        val info = alloc<stat>()
        if (statusAt(descriptor, name, info.ptr) != 0) throw errnoFailure(about = pathOf(name))
        read(info)
    }

    override fun child(name: String): Level {
        val opened = openAt(descriptor, name, FOLDER_OPEN, 0)
        if (opened >= 0) return DescriptorLevel(opened, pathOf(name))
        if (errno in MOVED_ERRNOS) throw LevelMoved(pathOf(name))
        throw errnoFailure(about = pathOf(name))
    }

    override fun makeFolder(
        name: String,
        mode: Int,
    ): Boolean {
        if (makeFolderAt(descriptor, name, mode) == 0) return true
        // Read before the lookup below, which can set errno again.
        val alreadyThere = errno == EEXIST
        val failure = errnoFailure(about = pathOf(name))
        if (alreadyThere && typeOf(name) == FileType.Directory) return false
        throw failure
    }

    override fun readLink(name: String): Path = readLinkIn(descriptor, name, about = pathOf(name))

    override fun createLink(
        name: String,
        target: Path,
    ) {
        if (symlinkAt(target.toString(), descriptor, name) != 0) throw errnoFailure(about = pathOf(name))
    }

    override fun remove(
        name: String,
        folder: Boolean,
    ) {
        if (removeAt(descriptor, name, folder) != 0) throw errnoFailure(about = pathOf(name))
    }

    override fun move(
        from: String,
        to: Level,
        toName: String,
    ) {
        val toFolder = (to as DescriptorLevel).descriptor
        if (renameAt(descriptor, from, toFolder, toName) != 0) throw errnoFailure(about = pathOf(from))
    }

    override fun renameNoReplace(
        from: String,
        to: String,
    ): NoReplaceRename = renameNoReplaceAt(descriptor, from, to, about = pathOf(from))

    override fun list(): List<String> {
        // fdopendir takes over the descriptor it is handed and closes it, so it gets a copy of this one.
        val copy = dup(descriptor)
        if (copy < 0) throw errnoFailure(about = path)
        val stream = fdopendir(copy)
        if (stream == null) {
            val failure = errnoFailure(about = path)
            close(copy)
            throw failure
        }
        try {
            // A dup shares the read offset, so a listing before this one would leave the stream at its end.
            rewinddir(stream)
            val names = mutableListOf<String>()
            while (true) {
                // readdir answers null both at the end and on a failure, which only errno tells apart.
                set_posix_errno(0)
                val entry = readdir(stream)
                if (entry == null) {
                    if (errno != 0) throw errnoFailure(about = path)
                    break
                }
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
            return names
        } finally {
            closedir(stream)
        }
    }

    override fun openAppend(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): Result<AppendFile, FileError> = openAppendFileAt(opening(name), permissions, newMode)

    override fun tryLock(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): OsLock = tryOsLockAt(opening(name), permissions, newMode)

    override fun close() {
        close(descriptor)
    }
}

/** [renameNoReplace] of [from] to [to] in the folder open as [folder], [about] naming the file in a failure. */
internal expect fun renameNoReplaceAt(
    folder: Int,
    from: String,
    to: String,
    about: Path,
): NoReplaceRename
