package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Every regular file under this path, and an error for each directory or entry that could not be read. Each
 * directory's own files come first, in name order, then its subdirectories are walked, also in name order, so
 * `a.txt`, `b/x` and `c.txt` walk as `a.txt`, `c.txt`, `b/x`. A regular file walks as itself. A FIFO, device
 * or socket inside a directory is skipped; as the root, it is [FileError.NotRegularFile].
 *
 * Symlinks are followed, and nothing tracks where the walk has been. A link pointing back up repeats that tree
 * at every level until the OS refuses to follow more links in one path (about 40 on Linux), and two such links
 * make the walk effectively endless. A symlink whose target is missing is skipped, and so is an entry that
 * cannot be looked up on the JVM and Apple targets, which kotlinx-io reports as missing there. On the JVM, a
 * directory that may not be read lists as empty.
 */
public fun Path.walkTopDown(): Sequence<Result<Path, FileError>> = sequence {
    val root = this@walkTopDown
    val type = when (val found = root.metadata()) {
        is Result.Success -> found.value.type
        is Result.Error -> {
            yield(found)
            return@sequence
        }
    }
    when (type) {
        FileType.Regular -> yield(Ok(root))
        FileType.Other -> yield(Err(FileError.NotRegularFile(root, type)))
        FileType.Directory -> yieldAll(filesUnder(root))
    }
}

private fun filesUnder(root: Path): Sequence<Result<Path, FileError>> = sequence {
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        val children = try {
            SystemFileSystem.list(directory).sortedBy { it.name }
        } catch (e: Exception) {
            yield(Err(FileError.Inaccessible(directory, e.reason)))
            continue
        }
        val subdirectories = mutableListOf<Path>()
        for (child in children) {
            when (val found = child.metadata()) {
                is Result.Success -> when (found.value.type) {
                    FileType.Regular -> yield(Ok(child))
                    FileType.Directory -> subdirectories += child
                    FileType.Other -> Unit
                }

                // Gone since the listing, or a symlink whose target is missing: there is nothing to walk.
                is Result.Error -> if (found.error !is FileError.NotFound) yield(found)
            }
        }
        pending.addAll(0, subdirectories)
    }
}
