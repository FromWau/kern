package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/**
 * Watches the file at [path] through [watch], seen through the view you need.
 *
 * ```kotlin
 * val config = FileWatcher(Path("app.toml"))
 * config.collect { change -> rescan() }                                          // every write
 * config.text(maxBytes = 1_048_576).collect { text -> text.onSuccess { reload(it) } }  // new text only
 * config.metadata.collect { info -> info.onSuccess { label.value = it.modifiedAt } }   // new info only
 * ```
 *
 * Every view answers the file's state when collection starts, so comparing that first answer with what you kept
 * covers the time you were not watching. Each view is cold: each collector watches on its own, and what it watches
 * with is released once it stops. How a change is noticed, and what cannot be watched, is as [changes] describes.
 */
public class FileWatcher(
    public val path: Path,
    public val watch: Watch = Watch.System(),
) {
    /**
     * The file's [info][com.fromwau.kern.fs.info] now, and again each time the file may have changed: on every write
     * the system tells of, even one that leaves the info as it was, and whenever a lookup finds other info. So the same
     * info can come again, as for a file opened for writing and closed unchanged, or a watched folder's mode changing.
     */
    public val changes: Flow<Result<FileInfo, FileError>> get() = path.everyChange(watch)

    /** The file's info now, and again each time it differs from the last answer, as [Path.changes] answers it. */
    public val metadata: Flow<Result<FileInfo, FileError>> get() = path.changes(watch)

    /** The file's text now, and again each time a read after a possible change differs, as [textChanges] reads it. */
    public fun text(
        maxBytes: Long,
        trusted: Boolean = false,
    ): Flow<Result<String, FileError>> = path.textChanges(maxBytes, trusted, watch)

    /**
     * Collects [changes].
     *
     * @param action called with each answer. A throw from this block is not caught, and ends the collection.
     */
    public suspend fun collect(action: suspend (Result<FileInfo, FileError>) -> Unit): Unit = changes.collect(action)
}
