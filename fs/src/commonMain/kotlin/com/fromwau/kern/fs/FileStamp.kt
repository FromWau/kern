package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlin.time.Instant

/**
 * What tells this file as it is now from the same file once written: which file it is, its size, modification time
 * and, where the platform keeps one, change time. Two stamps of one path are equal while nothing writes it. Give it to
 * [writeText] or [writeBytes] as `unchangedSince`, and the write refuses a file written since.
 *
 * ```kotlin
 * val stamp = config.stamp().getOrElse { return warn(it) }
 * val text = config.readText().getOrElse { return warn(it) }
 * config.writeText(edit(text), unchangedSince = stamp).onError { return warn(it) }
 * ```
 *
 * @return the stamp, following symlinks; [FileError.NotFound] when nothing is there, or [FileError.Inaccessible] when
 *   it may not be looked up.
 */
public fun Path.stamp(): Result<FileStamp, FileError.LookupError> {
    refusedPath()?.let { return it }
    return try {
        Ok(stampAt(this))
    } catch (e: Exception) {
        inaccessibleUnlessGone(e)
    }
}

/**
 * The file a [stamp] was taken of, as it was then. A write into the file itself that keeps its size and lands within
 * one tick of the file system's clock can pass unseen.
 */
public class FileStamp internal constructor(
    private val key: Any?,
    private val size: Long,
    private val modifiedAt: Instant,
    private val changedAt: Instant?,
) {
    override fun equals(other: Any?): Boolean = other is FileStamp && key == other.key && size == other.size &&
        modifiedAt == other.modifiedAt && changedAt == other.changedAt

    override fun hashCode(): Int = listOf(key, size, modifiedAt, changedAt).hashCode()

    override fun toString(): String = "FileStamp(size=$size, modifiedAt=$modifiedAt, changedAt=$changedAt)"
}

/** The [FileStamp] of [path], following symlinks, throwing why it could not be read. */
internal fun stampAt(path: Path): FileStamp {
    val info = fileInfoAt(path)
    return FileStamp(fileKeyOrNull(path), info.size, info.modifiedAt, info.changedAt)
}
