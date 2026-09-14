package com.fromwau.kern.dirs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/**
 * Replaces this file's content with [text] as UTF-8 by writing into the file itself, so its permissions, owner
 * and links stay as they are. The old content is first copied to a backup beside the file, `.<name>.<id>.bak`. A
 * failed write moves the backup back, which restores the content but not the metadata; a successful write then
 * tries to delete it, and a backup that cannot be deleted stays. When undoing a failed write fails as well, the
 * file is left damaged and the result is [FileError.RestoreFailed].
 *
 * The backup needs write permission on the folder, and a file that cannot be read or written is refused before
 * anything changes. A directory at the path is [FileError.NotRegularFile], while a FIFO or device there is written
 * into as it is, and opening a FIFO waits for a reader. Missing parent directories are created.
 *
 * This is not atomic, and only one write may run at a time, whether from other threads or other processes. A
 * process killed mid-write leaves the file half-written with the backup beside it, readers can see it half-written,
 * and two writes at once can leave a mix of both or undo each other. The backup has default permissions while it
 * exists. On Windows native, where kotlinx-io cannot follow links, a restore replaces a symlink with a regular file,
 * and undoing a failed first write through a symlink whose target is missing can remove the link instead of the
 * new file.
 */
public fun Path.writeText(text: String): EmptyResult<FileError> = writeBytes(text.encodeToByteArray())

/** [writeText] for raw bytes. */
public fun Path.writeBytes(bytes: ByteArray): EmptyResult<FileError> {
    val target = writeTarget()
    val created = target.parent?.createDirectories()
    if (created is Result.Error) return created
    val existing = when (val found = metadata()) {
        is Result.Success -> found.value
        is Result.Error -> if (found.error is FileError.NotFound) null else return found
    }
    if (existing?.isDirectory == true) return Err(FileError.NotRegularFile(this, FileType.Directory))

    // Only a regular file is backed up; a FIFO or device is written into as it is.
    var backup: Path? = null
    try {
        if (existing?.isRegularFile == true) {
            backup = target.backupBeside()
            copyFile(from = target, to = backup)
        }
    } catch (e: Exception) {
        backup?.deleteQuietly()
        return Err(FileError.WriteFailed(this, e.reason))
    }

    // Opening empties the file, so an open that fails has changed nothing and needs no restore.
    val sink = try {
        SystemFileSystem.sink(target)
    } catch (e: Exception) {
        backup?.deleteQuietly()
        return Err(FileError.WriteFailed(this, e.reason))
    }

    return try {
        sink.buffered().use { it.write(bytes) }
        backup?.deleteQuietly()
        Ok(Unit)
    } catch (e: Exception) {
        if (undo(backup, over = target, wasNew = existing == null)) {
            Err(FileError.WriteFailed(this, e.reason))
        } else {
            Err(FileError.RestoreFailed(this, e.reason, backup))
        }
    }
}

/**
 * Removes this file, symlink or empty directory. A symlink goes as a link, so what it points at stays. A
 * directory that still holds entries is [FileError.WriteFailed]; [deleteRecursively] clears a tree.
 *
 * kotlinx-io looks a path up before removing it, following symlinks, so a symlink whose target is missing reads
 * as [FileError.NotFound] and stays where it is.
 */
public fun Path.delete(): EmptyResult<FileError> = try {
    SystemFileSystem.delete(this, mustExist = true)
    Ok(Unit)
} catch (e: Exception) {
    Err(if (exists()) FileError.WriteFailed(this, e.reason) else FileError.NotFound(this))
}

/**
 * Removes this path and, when it is a directory, everything under it. Every entry goes as it is, so a symlink
 * is removed as a link and its target is untouched. The first failure stops the walk and is returned, which can
 * leave part of the tree behind.
 *
 * A symlink whose target is missing cannot be removed through kotlinx-io, so a tree holding one ends in
 * [FileError.WriteFailed] on the directory that still contains it.
 */
public fun Path.deleteRecursively(): EmptyResult<FileError> {
    // Removing first keeps a symlink a link: the tree below is only entered for a directory that resists.
    val removed = delete()
    if (removed !is Result.Error) return removed

    val children = when (val listed = list()) {
        is Result.Success -> listed.value
        // Not a directory after all, so the removal's own failure is the one that explains it.
        is Result.Error -> return removed
    }
    for (child in children) {
        val childRemoved = child.deleteRecursively()
        if (childRemoved is Result.Error) return childRemoved
    }
    return delete()
}

/**
 * Creates this directory and any missing parents, like `mkdir -p`. Succeeds when it already exists as a
 * directory or a symlink to one, also when another process creates it at the same time. A file at the path, or
 * in place of one of its parents, is [FileError.WriteFailed] naming that file.
 */
public fun Path.createDirectories(): EmptyResult<FileError> {
    // Only the levels below the deepest existing directory: on Windows, an ancestor can be a UNC server name.
    val missing = generateSequence(this) { it.parent }
        .takeWhile { it.metadata().getOrNull()?.isDirectory != true }
        .toList()
        .asReversed()
    // One level at a time: native kotlinx-io throws when another process creates a level first.
    for (level in missing) {
        val failure = try {
            SystemFileSystem.createDirectories(level)
            null
        } catch (e: Exception) {
            e
        }
        // Judged by what is there afterwards, since the JVM's mkdirs can also fail without throwing.
        if (level.metadata().getOrNull()?.isDirectory != true) {
            return Err(FileError.WriteFailed(level, failure?.reason ?: "Directory was not created"))
        }
    }
    return Ok(Unit)
}

/**
 * Undoes a write that failed after emptying [over]: moves [backup] back, or removes the file when it was new.
 * Returns whether that worked.
 */
private fun undo(
    backup: Path?,
    over: Path,
    wasNew: Boolean,
): Boolean = try {
    when {
        backup != null -> SystemFileSystem.atomicMove(backup, over)
        // Through a dangling symlink the new file sits at the link's target, which is what gets removed.
        wasNew && SystemFileSystem.exists(over) -> SystemFileSystem.delete(SystemFileSystem.resolve(over))
    }
    true
} catch (_: Exception) {
    false
}

private fun copyFile(
    from: Path,
    to: Path,
) {
    SystemFileSystem
        .source(from)
        .buffered()
        .use { source ->
            SystemFileSystem
                .sink(to)
                .buffered()
                .use { it.transferFrom(source) }
        }
}

/**
 * The file a symlink points at, so the backup and a restore land beside it and the link survives. Unresolvable
 * paths are used as given.
 */
private fun Path.writeTarget(): Path = try {
    if (SystemFileSystem.exists(this)) SystemFileSystem.resolve(this) else this
} catch (_: Exception) {
    this
}

/** A backup name beside this file, unique so a later write never overwrites a backup a killed one left. */
private fun Path.backupBeside(): Path {
    val sibling = ".${this.name}.${Uuid.random()}.bak"
    return parent?.let { Path(it, sibling) } ?: Path(sibling)
}

private fun Path.deleteQuietly() {
    try {
        SystemFileSystem.delete(this, mustExist = false)
    } catch (_: Exception) {
        // A leftover backup changes nothing about the outcome already decided.
    }
}
