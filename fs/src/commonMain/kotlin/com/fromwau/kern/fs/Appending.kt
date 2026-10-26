package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Opens this file for appending, creating it and any missing parent folders, and keeps it open until you close
 * the [AppendFile].
 *
 * ```kotlin
 * Path("build.log")
 *     .openAppend()
 *     .flatMap { log -> log.use { it.appendLine("started") } }
 *     .onError { println("not logged: $it") }
 * ```
 *
 * Each [AppendFile.append] lands at the file's end in one write, so lines appended by two processes to one local
 * file never tear each other. A symlink is followed and the file it points at is opened. For a log in a folder of your
 * app, [Writer.openAppend] never follows a link at the name.
 *
 * @param reopen whether the [AppendFile] keeps the file it opened, [Reopen.Never], or opens the path again once
 *   another tool has moved or deleted that file, [Reopen.WhenMoved].
 * @param ownerOnly leaves the file readable and writable by its owner alone, `600`, before the first append: a new
 *   file is created so, never wider for a moment, and an existing one is narrowed to it, as is each file a
 *   [Reopen.WhenMoved] reopen opens. A file this user does not own cannot be narrowed, which is
 *   [FileError.WriteFailed], as for a write's `ownerOnly`. Each missing folder made on the way is `700`, as
 *   [createDirectories] makes them with `ownerOnly`. It applies on Linux, Apple, Android and the JVM on a file system
 *   with POSIX permissions, and Windows ignores it.
 * @return the open file; [FileError.NotRegularFile] when a directory, FIFO, device or socket is at the path,
 *   [FileError.Inaccessible] when the path may not be looked up, the error creating the parent folders gave, or
 *   [FileError.WriteFailed] with the platform's cause when the file would not open or could not be narrowed. A path
 *   ending in `.` or `..` names a folder and is refused as [writeText] refuses it, before any folder is made.
 */
public fun Path.openAppend(
    reopen: Reopen = Reopen.Never,
    ownerOnly: Boolean = false,
): Result<AppendFile, FileError> = openAppend(reopen, TimeSource.Monotonic, ownerOnly)

/** [openAppend], timing [Reopen.WhenMoved.checkEvery] by [time]. */
internal fun Path.openAppend(
    reopen: Reopen,
    time: TimeSource,
    ownerOnly: Boolean = false,
): Result<AppendFile, FileError> = when (reopen) {
    Reopen.Never -> openOnce(ownerOnly)
    is Reopen.WhenMoved -> openOnce(ownerOnly).map { opened ->
        ReopeningAppendFile(opened, reopen, time) { openOnce(ownerOnly) }
    }
}

/**
 * When an [AppendFile] opens its path again, so a log another tool rotates by renaming or deleting it goes on in the
 * new file at the path.
 */
public sealed interface Reopen {
    /** The [AppendFile] keeps appending to the file it opened, wherever that file is moved. The default. */
    public data object Never : Reopen

    /**
     * Before an append, at most once per [checkEvery], the [AppendFile] asks [AppendFile.state], and on anything but
     * [AppendState.Same] closes the file and opens the path again, so that append lands in the new file. A file moved
     * between that check and the append still takes it, and so does one deleted then, with which that line is lost;
     * [AppendFile.state] answers [AppendState.Deleted] for it. A failed reopen is that append's error, and the next
     * append tries again. [Duration.ZERO], the default, checks before every append.
     *
     * ```kotlin
     * val log = file.openAppend(Reopen.WhenMoved(onReopen = { opened, _ -> opened.append(header).onError(::warn) }))
     * ```
     *
     * @param onReopen called with the file just opened at the path and the [AppendState] of the file it replaces:
     *   [AppendState.Moved], [AppendState.Deleted], or [AppendState.Unknown] where the platform cannot tell those apart
     *   or the state could not be looked up. It runs after each reopen and before the append that caused it, such as
     *   to write a header first, and appending to it there is appending to the new file. What it throws is thrown from
     *   that append, which then appends nothing.
     */
    public class WhenMoved(
        public val checkEvery: Duration = Duration.ZERO,
        public val onReopen: (opened: AppendFile, replaced: AppendState) -> Unit = { _, _ -> },
    ) : Reopen {
        override fun toString(): String = "WhenMoved(checkEvery=$checkEvery)"
    }
}

/** [openAppend] without reopening: the file now at this path, held open, made `600` first with [ownerOnly]. */
private fun Path.openOnce(ownerOnly: Boolean): Result<AppendFile, FileError> {
    refusedPath()?.let { return it }
    val entry = namedEntry()
    if (entry != this) return Err(folderWriteError(entry))
    val created = parent?.let { makeFolders(it, pathFolderMode(ownerOnly)) }
    if (created is Result.Error) return created
    when (val found = metadata()) {
        is Result.Success -> if (!found.value.isRegularFile) {
            return Err(FileError.NotRegularFile(this, typeOf(found.value)))
        }
        is Result.Error -> if (found.error !is FileError.NotFound) return found
    }
    return openAppendFile(this, OWNER_READ_WRITE.takeIf { ownerOnly })
}

/**
 * A file [openAppend] opened, which every [append] adds to at its end.
 *
 * Nothing here throws, except that an [append] throws what a [Reopen.WhenMoved]'s `onReopen` throws. Use it from one
 * thread at a time, and close it once no call is running: an [append] or [lastByte] after [close] fails with
 * [FileError.Closed]. Closing it again does nothing.
 */
public interface AppendFile : AutoCloseable {
    /** The path the file was opened at, which may name another file or nothing by now: see [state]. */
    public val path: Path

    /**
     * Appends [bytes] at the end of the file in one write, so another process appending to the same local file
     * lands before or after them, never inside. A network file system does not promise that.
     *
     * @return [FileError.WriteFailed] with the platform's cause, such as a full disk, when part of [bytes] may have
     *   been written; [FileError.Closed] after [close].
     */
    public fun append(bytes: ByteArray): EmptyResult<FileError>

    /**
     * Whether [path] still names this open file, as [AppendState] describes.
     *
     * @return the state; [FileError.Inaccessible] naming [path] when [path] or the open file could not be looked up
     *   for another reason than nothing being there, such as a folder on the way this process may not search.
     */
    public fun state(): Result<AppendState, FileError>

    /**
     * The last byte of the open file, or null when it is empty; only that byte is read, however long the file. Where
     * the JVM has no /proc, it reads the file at [path] instead.
     *
     * @return [FileError.Inaccessible] when it cannot be read, such as a file this process may only write;
     *   [FileError.Closed] after [close].
     */
    public fun lastByte(): Result<Byte?, FileError>
}

/**
 * Appends [text] as UTF-8 and a line break, `\n`, in one [AppendFile.append], so the line lands whole wherever that
 * call says its bytes do. A `\n` inside [text] is written as it is, in that same write.
 *
 * @return what [AppendFile.append] answers.
 */
public fun AppendFile.appendLine(text: String): EmptyResult<FileError> = append("$text\n".encodeToByteArray())

/** What became of the file an [AppendFile] has open, as seen from its [AppendFile.path]. */
public enum class AppendState {
    /** The path still names the open file. */
    Same,

    /** The open file still has a name, but the path names another file or nothing: it was moved or rotated. */
    Moved,

    /** The open file has no name left: it was deleted, and what is appended to it is lost once it is closed. */
    Deleted,

    /**
     * The path no longer names the open file, and this platform cannot tell whether it was moved or deleted: the
     * JVM on macOS and Windows.
     */
    Unknown,

    /** This [AppendFile] was closed, so there is no open file left to compare. */
    Closed,
}

/**
 * Opens [path], which is a regular file or nothing, for appending. With [permissions], a file the open creates is
 * never wider than them, and the file gets them before it is handed over, where the platform has POSIX permissions;
 * without, a file the open creates asks for [newMode], which the umask narrows.
 */
internal expect fun openAppendFile(
    path: Path,
    permissions: Int?,
    newMode: Int = NEW_FILE_MODE,
): Result<AppendFile, FileError>

/**
 * An [AppendFile] that opens its path again with [openAgain], as [reopen] describes, timing its checks by [time].
 * [state] and [lastByte] describe the file open now.
 */
internal class ReopeningAppendFile(
    private var open: AppendFile,
    private val reopen: Reopen.WhenMoved,
    private val time: TimeSource,
    private val openAgain: () -> Result<AppendFile, FileError>,
) : AppendFile {
    override val path: Path get() = open.path

    private var lastCheck = time.markNow()

    // Set by a reopen that failed, so the next append tries again whatever checkEvery says.
    private var reopenFailed = false

    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.Closed(path))
        if (reopenFailed || lastCheck.elapsedNow() >= reopen.checkEvery) {
            lastCheck = time.markNow()
            val state = open.state()
            if (state != Ok(AppendState.Same)) {
                // Opened before the old file is closed, so a failure leaves this AppendFile on the file it had.
                val reopened = when (val opened = openAgain()) {
                    is Result.Success -> opened.value
                    is Result.Error -> {
                        reopenFailed = true
                        return opened
                    }
                }
                reopenFailed = false
                open.close()
                open = reopened
                reopen.onReopen(reopened, state.getOrNull() ?: AppendState.Unknown)
            }
        }
        return open.append(bytes)
    }

    override fun state(): Result<AppendState, FileError> = if (closed) Ok(AppendState.Closed) else open.state()

    override fun lastByte(): Result<Byte?, FileError> = if (closed) Err(FileError.Closed(path)) else open.lastByte()

    override fun close() {
        closed = true
        open.close()
    }
}

/** [file], opened by a stage's writer, its errors naming each path in the stage as [shown] names it once placed. */
internal class ShownAppendFile(
    private val file: AppendFile,
    private val shown: (FileError) -> FileError,
) : AppendFile by file {
    override fun append(bytes: ByteArray): EmptyResult<FileError> = file.append(bytes).mapError(shown)

    override fun state(): Result<AppendState, FileError> = file.state().mapError(shown)

    override fun lastByte(): Result<Byte?, FileError> = file.lastByte().mapError(shown)
}
