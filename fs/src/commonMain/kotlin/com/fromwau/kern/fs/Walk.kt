package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/**
 * Every regular file under this path, and an error for each directory or entry that could not be read. Each
 * directory's own files come first, in name order, then its subdirectories are walked, also in name order, so
 * `a.txt`, `b/x` and `c.txt` walk as `a.txt`, `c.txt`, `b/x`. A regular file walks as itself. A FIFO, device
 * or socket inside a directory is skipped; as the root, it is [FileError.NotRegularFile].
 *
 * [symlinks] says whether a folder reached through a symlink is entered, as [Symlinks] describes. A link to a
 * file is yielded either way, and one whose target is missing is skipped. The root is walked even when it is
 * itself a link.
 */
public fun Path.walkTopDown(symlinks: Symlinks = Symlinks.Skip): Sequence<Result<Path, FileError>> = sequence {
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
        FileType.Directory -> yieldAll(filesUnder(root, symlinks))
    }
}

private fun filesUnder(
    root: Path,
    symlinks: Symlinks,
): Sequence<Result<Path, FileError>> = sequence {
    val pending = ArrayDeque(listOf(root))
    // Where each folder really is, so one reached again through a link is recognised. Only a followed link can
    // reach a folder twice.
    val entered = if (symlinks == Symlinks.Follow) mutableSetOf(realPath(root)) else null
    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        val children = when (val listed = directory.list()) {
            is Result.Success -> listed.value
            is Result.Error -> {
                yield(listed)
                continue
            }
        }
        val subdirectories = mutableListOf<Path>()
        for (child in children) {
            when (val found = child.metadata()) {
                is Result.Success -> when (found.value.type) {
                    FileType.Regular -> yield(Ok(child))
                    FileType.Directory -> when {
                        entered == null -> if (!isSymlink(child)) subdirectories += child
                        entered.add(realPath(child)) -> subdirectories += child
                    }
                    FileType.Other -> Unit
                }

                // Gone since the listing, or a symlink whose target is missing: there is nothing to walk.
                is Result.Error -> if (found.error !is FileError.NotFound) yield(found)
            }
        }
        pending.addAll(0, subdirectories)
    }
}
