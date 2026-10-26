package com.fromwau.kern.fs

import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlin.time.Instant

/**
 * Opens the file at [path], following symlinks, for reading, throwing what the platform said when it will not open.
 * Kern opens it itself wherever it can, since kotlinx-io's open says why only in its message. On Linux and Apple,
 * anything at [path] but a regular file is refused with [NotRegularFileFailure], without waiting on a FIFO. The JVM,
 * Android and Windows native refuse it the same way by looking it up before the open, so only a FIFO put there in
 * between is waited on. With [followLinks] false, a symlink at [path]'s last name is refused rather than followed,
 * except on Windows native. With [streams], a FIFO or a character device is opened too, waiting for a FIFO's writer;
 * on Windows native a named pipe is opened as its client, which does not wait, both by the lookup and by the open.
 */
internal expect fun openFileSource(
    path: Path,
    followLinks: Boolean = true,
    streams: Boolean = false,
): FileSource

/** A source on one file, as [openFileSource] opens it, that can also say what that file is. */
internal interface FileSource : RawSource {
    /**
     * What the open file is, as [Path.info] describes it: read from the open file itself on Linux and Apple, and from
     * its path elsewhere. Throws why it could not be read, also when the path no longer names the open file where the
     * platform can tell.
     */
    fun info(): FileInfo
}

/**
 * Opens the file at [path], following symlinks and creating it when missing, for writing at its end when [append]
 * is true and emptied otherwise, throwing what the platform said when it will not open. With [permissions] the file
 * gets those permission bits before it is emptied, where the platform has POSIX permissions, and a file the open
 * creates is never wider than them; without, a file the open creates asks for [newMode], which the umask narrows.
 * Kern opens it itself wherever it can, and refuses what is no regular file, and with [followLinks] false a link, as
 * [openFileSource] does.
 */
internal expect fun openFileSink(
    path: Path,
    append: Boolean,
    permissions: Int? = null,
    followLinks: Boolean = true,
    newMode: Int = NEW_FILE_MODE,
): FileSink

/** A sink on one file, as [openFileSink] opens it, that can also set that file's modification time and mode. */
internal interface FileSink : RawSink {
    /** Sets the file's modification time to [time], to the precision the file system keeps. */
    fun setModifiedTime(time: Instant)

    /** The file's own permission bits, as [permissionBits] reads them, null where the platform has none. */
    fun permissionBits(): Int?

    /** Sets the file's own permission bits to [bits]; a platform with none does nothing. */
    fun setPermissions(bits: Int)

    /** What the open file is, as [Path.info] describes it, read from the file itself but on the JVM and Android. */
    fun info(): FileInfo
}

/**
 * Creates the file at [path] and holds it open for reading and writing, throwing what the platform said when it will
 * not open, also when anything is already at [path]. With [permissions] it is created no wider than them and then
 * given them, where the platform has POSIX permissions; without, it asks for [newMode], which the umask narrows.
 */
internal expect fun createHeldFile(
    path: Path,
    permissions: Int? = null,
    newMode: Int = NEW_FILE_MODE,
): HeldFile

/** An open kern refused because what is at the path is no regular file but a [type]. */
internal class NotRegularFileFailure(val type: FileType) : IOException("not a regular file but a $type")

/**
 * What a write of [path] that failed this way answers: the refused open's own error, or [FileError.WriteFailed] naming
 * where it was refused when [failingAt] marked that.
 */
internal fun Exception.writeError(path: Path): FileError {
    val (failure, at) = located()
    return (failure as? NotRegularFileFailure)?.let { FileError.NotRegularFile(path, it.type) }
        ?: writeFailed(path, failure, at)
}

/**
 * A file [createHeldFile] opened and keeps open, so the permissions it is given, such as a read-only file's, never
 * stop it being written or read back, as a reopen would be stopped. Windows native opens it again by its path for
 * each call but [info]. Every call throws what the platform said.
 */
internal interface HeldFile : AutoCloseable {
    /** Sets the file's permission bits to [bits], where the platform has permissions. */
    fun setPermissions(bits: Int)

    /** Writes after what the file holds; closing the sink leaves the file open. */
    fun sink(): RawSink

    /** Reads the file from its first byte; closing the source leaves the file open. */
    fun source(): RawSource

    /** Sets the file's modification time to [time], to the precision the file system keeps. */
    fun setModifiedTime(time: Instant)

    /** Renames the file to [target], in one step and on the same file system, and keeps it open under that name. */
    fun moveTo(target: Path)

    /** Forces what was written to disk. */
    fun sync()

    /** What the held file is, as [FileSource.info] reads it, from the file itself where the platform can. */
    fun info(): FileInfo
}
