package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.io.IOException
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlin.time.Duration

/** Why a file operation failed. Every case names the [path] it is about. */
public sealed interface FileError : ResultError {
    public val path: Path

    /**
     * Nothing is at [path]: it is missing, a symlink whose target is missing, or a path that runs through a file
     * and so cannot resolve. A path that could not be looked up is [Inaccessible] instead, on every target.
     */
    public data class NotFound(override val path: Path) : FileError

    /**
     * Something is already at [path], a symlink whose target is missing included, where [createSymlink] or
     * [createDirectoryAtomically] would make something new and never replaces what is there, or something that is no
     * link or folder is where [switchSymlinkTo] would replace a link. [type] is what is there,
     * read without following a link as [fileType] with `followLinks = false` reads it, so any link is
     * [FileType.Symlink]; it is [FileType.Other] when it could no longer be looked up.
     */
    public data class AlreadyExists(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /**
     * [path] could not be read or looked up, for the [cause] the platform gave in [exception], when it gave one. Two
     * are equal when [path] and [cause] are, whatever their [exception].
     */
    public data class Inaccessible(
        override val path: Path,
        val cause: IoCause,
        val exception: Throwable?,
    ) : FileError {
        override fun equals(other: Any?): Boolean = other is Inaccessible && path == other.path && cause == other.cause

        override fun hashCode(): Int = hashOf(path, cause)
    }

    /**
     * A directory, FIFO, device or socket stands where a regular file was expected, or a symlink, where the call never
     * writes through one, as [mirrorTo] and [Writer] calls do.
     */
    public data class NotRegularFile(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /** [readSymlink] found no symlink at [path] but a [type]. */
    public data class NotASymlink(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /**
     * A file, FIFO, device, socket or symlink stands where a directory was expected. [type] is what is there
     * instead, [FileType.Symlink] for a link a folder cannot replace.
     */
    public data class NotADirectory(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /**
     * [path] holds at least [atLeastBytes] bytes, more than the caller's limit of [limitBytes]. It is a lower
     * bound: a read that crosses the limit stops there instead of measuring the rest.
     */
    public data class TooLarge(
        override val path: Path,
        val limitBytes: Long,
        val atLeastBytes: Long,
    ) : FileError

    /**
     * A check refused what is at [path], which [info] describes, for the reason [why] gives: the `accept` check given
     * to [readText] or [readBytes], as the file was once opened, or the check a `trusted` read makes of that file and
     * its folder, and nothing was read; the check [singleInstance] makes of its `state` folder, which someone else owns
     * or the group or others may write, and nothing was locked or written; or the check a [Writer] makes of its root, a
     * file it reads or a folder on the way, and nothing was done. [asked] is the path the call was given, which [path]
     * differs from when the refused place is a folder on the way to it, or a file read by its real path, as through a
     * link at the path or on the way. Printed, it shows [path], [asked] when it differs, [why] and the permissions
     * [info] holds, as `Refused(/srv/app: OthersMayWrite, rwxrwxrwx)` or
     * `Refused(/srv/app for /srv/app/config: OtherOwner)`.
     */
    public data class Refused(
        override val path: Path,
        val info: FileInfo,
        val why: RefusedBecause,
        val asked: Path,
    ) : FileError {
        override fun toString(): String {
            val place = if (asked == path) "$path" else "$path for $asked"
            return "Refused($place: $why${info.permissions?.let { ", $it" } ?: ""})"
        }
    }

    /**
     * A [WriteMode.Atomic] write refused to replace [path], since a rename would change it in the way [why] says.
     * Nothing was written.
     */
    public data class AtomicRefused(
        override val path: Path,
        val why: AtomicRefusal,
    ) : FileError

    /**
     * The name or link target a call was given is not one this call makes, as the case says: [InvalidRelativePath],
     * [Escapes], [InvalidName] or [NameTooLong]. Nothing was changed.
     */
    public sealed interface NameRefused : FileError

    /**
     * A name of [bytes] in UTF-8, longer than the 255 a name may take: one in [path] itself, or one a write of [path],
     * [createDirectoryAtomically] or [switchSymlinkTo] would make beside it, as [name] says. Nothing was created.
     * Printed, each name in [path] over 64 characters is cut there and followed by its length in bytes.
     */
    public data class NameTooLong(
        override val path: Path,
        val name: LongName,
        val bytes: Int,
    ) : NameRefused {
        override fun toString(): String = "NameTooLong(path=${path.namesCutToPrint()}, name=$name, bytes=$bytes)"
    }

    /**
     * [path] holds what no name may, as [reason] says, or the name [singleInstance] was given is not one name; or, as
     * [NameProblem.LinksToItself], [Writer.switchSymlink] was asked to point the link [path] at itself. Nothing was
     * looked up or created. Printed, a NUL in [path] shows as `\0`, and [path] itself keeps it.
     */
    public data class InvalidName(
        override val path: Path,
        val reason: NameProblem,
    ) : NameRefused {
        override fun toString(): String =
            "InvalidName(path=${path.toString().replace("${Char(0)}", "\\0")}, reason=$reason)"
    }

    /**
     * A [Writer] was asked to act on [path], which it may not reach: a symlink on the way there leads out of
     * [boundary], the folder the writer keeps to, or steps out of it on its way back in; or [path] lies outside the
     * writer's root, as a leftover handed to [Writer.discard] can; or the target [Writer.switchSymlink] was given for
     * the link [path] leads out of [boundary] by its names. [boundary] is the writer's own root, for a child writer
     * the boundary of the writer it was made from, and for a stage the folder it builds, named as it will be placed.
     * [resolved] is where it leads:
     * - for a link on the way, the real path of the folder it names;
     * - for a link at [path] itself, or the last link of a chain there, or a switched link's target, that target
     *   joined to the link's folder and read by name, `/etc` for `current -> /etc`;
     * - for a path outside the root, its own folder.
     *
     * Nothing was read, written, created, moved, removed or locked.
     */
    public data class Escapes(
        override val path: Path,
        val boundary: Path,
        val resolved: Path,
    ) : NameRefused

    /**
     * Writing, deleting or moving the file, or creating a directory, failed, for the [cause] the platform gave in
     * [exception], if any. [at] is the spot the system refused when it is not [path] itself, and null otherwise, as
     * when writing the content failed: a temp or stage, a backup, the file a link at [path] leads to, a folder on the
     * way, or a claim, as [FailedAt] lists them. Two are equal when every field but [exception] is.
     */
    public data class WriteFailed(
        override val path: Path,
        val cause: IoCause,
        val exception: Throwable?,
        val at: FailedAt? = null,
    ) : FileError {
        override fun equals(other: Any?): Boolean =
            other is WriteFailed && path == other.path && cause == other.cause && at == other.at

        override fun hashCode(): Int = hashOf(path, cause, at)
    }

    /**
     * A write in place, by [writeText], [writeBytes] or [copyTo], failed after emptying [path], and undoing it
     * failed too, so [path] is damaged; or [Writer.restore] failed after emptying it.
     *
     * [backup] is the copy holding the old content, or null when there is none to put back: the file was new,
     * or the copy is no longer there. [cause] and [exception] are why putting the old content back failed, which is
     * what left the file damaged; a failed write's own failure is the condition this is a consequence of. [at] is the
     * spot the system refused when it is not [path] itself, such as [FailedAt.Backup] for the backup it was putting
     * back, and null otherwise. Two are equal when every field but [exception] is.
     */
    public data class RestoreFailed(
        override val path: Path,
        val cause: IoCause,
        val exception: Throwable?,
        val backup: Path?,
        val at: FailedAt? = null,
    ) : FileError {
        override fun equals(other: Any?): Boolean =
            other is RestoreFailed &&
                path == other.path &&
                cause == other.cause &&
                backup == other.backup &&
                at == other.at

        override fun hashCode(): Int = 31 * hashOf(path, cause, at) + backup.hashCode()
    }

    /**
     * Another holder kept the lock on [path] for longer than the wait, in this process or another. [waited] is
     * how long the attempt waited, zero for [tryLock].
     *
     * [holderPid] names the holder: this process's own id when the holder is in this process, on every platform.
     * Another process is named on native Linux and Apple. It is null on the JVM and Android, where a `FileChannel`
     * cannot say who holds a lock, and on Windows native, which names no holder, except from [singleInstance], which
     * then reads the pid from its pid file.
     */
    public data class LockBusy(
        override val path: Path,
        val holderPid: Long?,
        val waited: Duration,
    ) : FileError

    /**
     * [withRunTemp] found the run temp folder at [path] already claimed: a [withRunTemp] on the same `AppDirs` is still
     * running, in this process or another, or another process holds the same fixed run id. Nothing was created or
     * cleared. [holderPid] names the holder as [LockBusy.holderPid] does.
     */
    public data class RunTempClaimed(
        override val path: Path,
        val holderPid: Long?,
    ) : FileError

    /**
     * The lock on [path] could not be attempted: the file would not open, or the OS refused the call, for the [cause]
     * the platform gave in [exception], if any. Two are equal when [path] and [cause] are, whatever their [exception].
     */
    public data class LockFailed(
        override val path: Path,
        val cause: IoCause,
        val exception: Throwable?,
    ) : FileError {
        override fun equals(other: Any?): Boolean = other is LockFailed && path == other.path && cause == other.cause

        override fun hashCode(): Int = hashOf(path, cause)
    }

    /**
     * A lock still held is no longer on the file at [path]: that file was deleted, or replaced by another, so a new
     * holder can take a lock there and run beside this one. [FileLock.check] and [InstanceLock.check] answer it.
     */
    public data class LockLost(override val path: Path) : FileError

    /** The [AppendFile] open at [path] was used after it was closed. */
    public data class Closed(override val path: Path) : FileError

    /**
     * [mirrorTo] was asked to mirror the folder at [path] into the [Writer] whose root is [dst], and [path] is [dst] or
     * lies below it, by the [realPath] of [path], so removing what the source does not hold would delete the source.
     * Nothing was copied or removed.
     */
    public data class MirrorOverlap(
        override val path: Path,
        val dst: Path,
    ) : FileError

    /**
     * The folder a [Writer] was made for is no longer at [path], its real path: it was moved or deleted, or another
     * folder or a link stands there now. Nothing was done.
     */
    public data class FolderReplaced(override val path: Path) : FileError

    /**
     * [entry], the name a [Writer] was given, is no relative path below its root [folder], for the [problem] given.
     * Nothing was looked up or done. [path] is [folder]. Printed, a NUL in [entry] shows as `\0`, and an [entry] over
     * 64 characters is cut there and followed by its length in bytes.
     */
    public data class InvalidRelativePath(
        val folder: Path,
        val entry: String,
        val problem: RelativePathProblem,
    ) : NameRefused {
        override val path: Path get() = folder

        override fun toString(): String {
            val printed = "InvalidRelativePath(folder=$folder, entry=${entry.cutToPrint()}, problem=$problem)"
            return printed.replace("${Char(0)}", "\\0")
        }
    }

    /**
     * A write of [path], by a [Writer] or [writeText], [writeBytes] or [copyTo], found [backup] beside it, or beside
     * the target of a link at [path], the old content a killed in-place write copied, so writing now could lose what
     * it holds. Nothing was written. [Writer.restore] or [Writer.discard] it, and the write goes through; with
     * several, [backup] is the oldest.
     */
    public data class PendingBackup(
        override val path: Path,
        val backup: Leftover.Backup,
    ) : FileError

    /**
     * Two entries of a [mirrorTo] source go to one output, [path] in the destination. The entry at [firstRelative],
     * relative to the source, took it first in walk order, and this one was neither copied nor made, nor anything below
     * it, on every run.
     */
    public data class OutputClash(
        override val path: Path,
        val firstRelative: Path,
    ) : FileError
}

/**
 * Where a [FileError.WriteFailed] or [FileError.RestoreFailed] was refused, when that is not its own `path`: the kind
 * of spot, and its [path]. Show a user [path], worded by the kind, such as "could not make the folder $path".
 */
public sealed interface FailedAt {
    /** The path the system refused. */
    public val path: Path

    /**
     * A temp beside the file, `.<name>.<id>.tmp`, that a write renames into place, or the stage `.<name>.<id>.dir`
     * or `.<name>.<id>.link` a folder or a link is built as before it is moved into place.
     */
    public data class Temp(override val path: Path) : FailedAt

    /** A backup beside the file, `.<name>.<id>.bak`, or the `.bak.part` it is copied into first. */
    public data class Backup(override val path: Path) : FailedAt

    /** The file a symlink at the error's `path` leads to, which the write goes through to. */
    public data class LinkTarget(override val path: Path) : FailedAt

    /** A folder on the way to the file, which could not be made. */
    public data class Folder(override val path: Path) : FailedAt

    /**
     * The lock file that claims a stage while it is built, `.<name>.<id>.lock` beside it, or a run's temp folder while
     * it is used, `.<run id>.lock` beside it.
     */
    public data class Claim(override val path: Path) : FailedAt
}

/**
 * This result, with [FileError.NotFound] turned into success with [value]; any other error, and a success, are left
 * as they are.
 *
 * ```kotlin
 * val text = settings
 *     .readText()
 *     .orIfNotFound { DEFAULT_SETTINGS }   // a missing file reads as the defaults
 *     .getOrElse { return warn(it) }
 * ```
 *
 * @param value called only on [FileError.NotFound].
 */
public inline fun <S> Result<S, FileError>.orIfNotFound(value: () -> S): Result<S, FileError> =
    if (this is Result.Error && error is FileError.NotFound) Ok(value()) else this

/**
 * This result, with [FileError.NotFound] turned into a success holding null; any other error, and a success, are left
 * as they are.
 *
 * ```kotlin
 * val token = (dirs.config / "token").readText().orNullIfNotFound().getOrElse { return warn(it) }
 * if (token == null) println("not logged in")
 * ```
 */
public fun <S> Result<S, FileError>.orNullIfNotFound(): Result<S?, FileError> = orIfNotFound<S?> { null }

private fun hashOf(
    path: Path,
    cause: IoCause,
    at: FailedAt? = null,
): Int = 31 * (31 * path.hashCode() + cause.hashCode()) + at.hashCode()

/**
 * What the platform said went wrong. It is typed only where the platform says so without its message being read: on
 * the JVM and Android by the exception's type, and on native targets by the error code of a call kern makes itself.
 * A failure left untyped is [Loop] when kern follows the symlinks on the way to the path itself and they go round.
 * Anything else is [Other], with the platform's exception beside it.
 */
public enum class IoCause {
    /** This process may not do it: the permissions refuse it. */
    Denied,

    /** A file or folder the operation needed is not there. */
    NotFound,

    /** The file system is mounted read-only. */
    ReadOnly,

    /** The disk, or this user's quota on it, is full. */
    NoSpace,

    /** Resolving the path met a symlink loop, or too many links in a row. */
    Loop,

    /** Anything else, or a failure the platform does not type. */
    Other,
    ;

    /** Holds the mappings from a platform's own failures to a cause, for kern's own modules. */
    public companion object
}

/** Which name [FileError.NameTooLong] found too long. */
public enum class LongName {
    /** A name in the path the call was given, the last one or a folder's on the way to it. */
    Path,

    /** The `.<name>.<id>.bak.part` an in-place write copies the old content into before it becomes the backup. */
    PartialBackup,

    /**
     * The stage `.<name>.<id>.dir` [createDirectoryAtomically] builds the folder in before putting it in place, or the
     * claim `.<name>.<id>.lock` it holds beside the stage while it builds, a byte longer, which is the one counted.
     */
    Stage,

    /** The `.<name>.<id>.link` [switchSymlinkTo] makes beside the link before moving it into place. */
    StagedLink,
}

/** What keeps a string from being a name: what [FileError.InvalidName] found, or [nameProblem] found in one name. */
public enum class NameProblem {
    /** A NUL character, which a native file system call reads as the end of the path. */
    Nul,

    /** Nothing, or only whitespace. */
    Blank,

    /** `.`, which names the folder it is in. */
    Dot,

    /** `..`, which names the folder above. */
    DotDot,

    /** A `/` or a `\`, which would make it more than one name. */
    Separator,

    /**
     * Longer than 255 bytes in UTF-8. Every fs call refuses a path holding such a name as [FileError.NameTooLong];
     * [singleInstance] refuses a name whose lock file's name `.<name>.lock` would be as [FileError.InvalidName].
     */
    TooLong,

    /**
     * Longer than [MAX_STAGED_NAME_BYTES] and no longer than 255 bytes in UTF-8, as `nameProblem(staged = true)` judges
     * a name: too long for the link or claim the calls [MAX_STAGED_NAME_BYTES] lists make beside it, which they answer
     * as [FileError.NameTooLong].
     */
    TooLongToStage,

    /**
     * Shaped like a copy a write leaves beside its file, such as `.notes.md.<id>.tmp`, which a leftover scan would
     * list as one: what [nameProblem] answers last, and why a [Writer] refuses to make an entry with such a name.
     */
    LeftoverName,

    /** The target [Writer.switchSymlink] was given names the link itself, so following it would only go round. */
    LinksToItself,
}

/** Why [FileError.Refused] refused what is at its path. */
public enum class RefusedBecause {
    /** The `accept` check given to [readText] or [readBytes] answered false. */
    Accept,

    /** Another user owns it. */
    OtherOwner,

    /** Its group or everyone else may write it, so someone besides its owner may change it. */
    OthersMayWrite,

    /**
     * Its group or everyone else may read it, so someone besides its owner may see what it holds: what a private
     * [Writer] refuses of its root and of a file it reads.
     */
    OthersMayRead,
}

/** Why a [WriteMode.Atomic] write would not replace a file by rename. */
public enum class AtomicRefusal {
    /** The file has other hard links, which a rename would cut off: they would keep the old content. */
    OtherHardLinks,

    /** Another user owns the file, and a rename would make it this user's. */
    OtherOwner,

    /** The new file could not be given the old one's group, so a rename would change who may share it. */
    GroupNotKept,

    /**
     * The path is a symlink this platform cannot follow, and a rename would put a regular file in the link's
     * place. Only Windows native answers this.
     */
    Symlink,
}

/**
 * What is at a path. A FIFO, a device or a socket is told apart where the platform says what it is: Linux, Apple, and
 * the JVM on a file system with unix attributes. Android reads the same attribute, which has not been tested on a
 * device. Windows native, unverified there, calls a device such as `NUL` or `COM1` a [CharacterDevice] and a named
 * pipe a [Fifo], and looking one up opens it, so looking up a named pipe connects to its server. Elsewhere, such as on
 * the JVM on Windows, each of them is [Other].
 */
public enum class FileType {
    Regular,
    Directory,

    /** A named pipe. */
    Fifo,

    /** A stream of bytes such as `/dev/null`, `/dev/zero` or `/dev/tty`. */
    CharacterDevice,

    /** A disk or partition such as `/dev/sda`. */
    BlockDevice,

    /** A unix domain socket. */
    Socket,

    /**
     * A symlink itself, whatever it leads to: what [fileType] answers with `followLinks = false`, and what [moveTo]
     * answers as the [FileError.NotADirectory] of a folder moved onto a link. [info] and [fileType] by default follow
     * links, so they never answer it.
     */
    Symlink,

    /** Something this platform cannot name, or one it does not tell apart from the others. */
    Other,
}

/** What [metadata], read at this path, says is there; anything but a file or folder is asked of the platform. */
internal fun Path.typeOf(metadata: FileMetadata): FileType = when {
    metadata.isRegularFile -> FileType.Regular
    metadata.isDirectory -> FileType.Directory
    else -> fileTypeAt(this)
}

/** What is at [path] as [info] reads it, following symlinks, or [FileType.Other] when the platform cannot say. */
internal fun fileTypeAt(path: Path): FileType = try {
    fileInfoAt(path).type
} catch (_: Exception) {
    FileType.Other
}

/** The type a posix `st_mode` names, the same bits on Linux, Apple and the JVM's `unix:mode`. */
internal fun fileTypeOfMode(mode: Int): FileType = when (mode and MODE_TYPE_BITS) {
    MODE_REGULAR -> FileType.Regular
    MODE_DIRECTORY -> FileType.Directory
    MODE_FIFO -> FileType.Fifo
    MODE_CHARACTER_DEVICE -> FileType.CharacterDevice
    MODE_BLOCK_DEVICE -> FileType.BlockDevice
    MODE_SOCKET -> FileType.Socket
    MODE_SYMLINK -> FileType.Symlink
    else -> FileType.Other
}

/** Whether a read takes this type: a regular file, or with [streams] a FIFO or a character device too. */
internal fun FileType.isReadable(streams: Boolean): Boolean =
    this == FileType.Regular || streams && (this == FileType.Fifo || this == FileType.CharacterDevice)

private const val MODE_TYPE_BITS = 0xF000
private const val MODE_FIFO = 0x1000
private const val MODE_CHARACTER_DEVICE = 0x2000
private const val MODE_DIRECTORY = 0x4000
private const val MODE_BLOCK_DEVICE = 0x6000
private const val MODE_REGULAR = 0x8000
private const val MODE_SYMLINK = 0xA000
private const val MODE_SOCKET = 0xC000

/** [path] could not be read or looked up, for what [failure] says, or for no cause kern can name without it. */
internal fun inaccessible(
    path: Path,
    failure: Throwable?,
): FileError.Inaccessible = FileError.Inaccessible(path, failure.causeAt(path), failure)

/**
 * What [this] failure at [path] says went wrong, or [IoCause.Loop] where it names no cause and looking [path] up
 * follows symlinks round: the JVM reports a loop at the last name or in a folder above it as a bare
 * FileSystemException.
 */
internal fun Throwable?.causeAt(path: Path): IoCause {
    val cause = this?.ioCause() ?: IoCause.Other
    return if (cause == IoCause.Other && path.linksGoRound()) IoCause.Loop else cause
}

/**
 * Writing [path] failed, for what [failure] says, or for no cause kern can name without it, refused at [at] when that
 * is another path.
 */
internal fun writeFailed(
    path: Path,
    failure: Throwable?,
    at: FailedAt? = null,
): FileError.WriteFailed =
    FileError.WriteFailed(path, failure.causeAt(at?.path ?: path), failure, at.unlessAt(path))

/**
 * Undoing a failed in-place write of [path] failed, for what [failure] says, refused at the path [failingAt] marked
 * when that is another path, with [backup] left to restore from.
 */
internal fun restoreFailed(
    path: Path,
    failure: Exception,
    backup: Path?,
): FileError.RestoreFailed {
    val (located, at) = failure.located()
    return FileError.RestoreFailed(path, located.ioCause(), located, backup, at.unlessAt(path))
}

/** This spot, or null when its path is only another spelling of [path], as [unlessSameAs] judges it. */
private fun FailedAt?.unlessAt(path: Path): FailedAt? = this?.takeIf { it.path.unlessSameAs(path) != null }

/**
 * This path, or null when it is only another spelling of [path], which an error already names. What a symlink at
 * [path] leads to is another path, and stays.
 */
internal fun Path?.unlessSameAs(path: Path): Path? {
    if (this == null || this == path) return null
    if (isSymlink(path)) return this
    return if (oneName() == path.oneName()) null else this
}

/** [failure] of a call kern made on [at], carried up to the error that reports it. */
internal class LocatedFailure(
    val at: FailedAt,
    val failure: Exception,
) : Exception(failure)

/** Runs [block], marking what it throws as a failure at [at], unless a step inside it already named its own spot. */
internal inline fun <T> failingAt(
    at: FailedAt,
    block: () -> T,
): T = try {
    block()
} catch (e: LocatedFailure) {
    throw e
} catch (e: Exception) {
    throw LocatedFailure(at, e)
}

/** The failure this exception stands for, and the spot it was made on when [failingAt] named one. */
internal fun Exception.located(): Pair<Exception, FailedAt?> =
    if (this is LocatedFailure) failure to at else this to null

/** The cause [failure] stands for, read from its type alone, as every fs call that reports one types it. */
@InternalKernApi
public fun IoCause.Companion.of(failure: Throwable): IoCause = failure.ioCause()

/** What [this] failure says went wrong, read from its type alone. */
internal fun Throwable.ioCause(): IoCause = (this as? OsCallFailure)?.ioCause ?: platformCause()

/** The cause the platform's own exception types stand for, [IoCause.Other] where it has none. */
internal expect fun Throwable.platformCause(): IoCause

/** A failed call kern made to the OS itself on [path], typed from the error code that call set, in [words]. */
internal class OsCallFailure(
    val ioCause: IoCause,
    path: Path,
    words: String,
) : IOException("$path: $words") {
    override val message: String get() = super.message.orEmpty()

    // Printed where a caller shows a FileError, which should not name a class internal to this library.
    override fun toString(): String = message
}

/** The `errno` names kern types, each with the cause it stands for. */
internal enum class Errno(val cause: IoCause) {
    EACCES(IoCause.Denied),
    EPERM(IoCause.Denied),
    ENOENT(IoCause.NotFound),
    EROFS(IoCause.ReadOnly),
    ENOSPC(IoCause.NoSpace),
    EDQUOT(IoCause.NoSpace),
    ELOOP(IoCause.Loop),
}

/**
 * The cause the `errno` value [code] stands for, where [valueOf] gives a platform's value for each name, or null for
 * a name it lacks.
 */
internal fun causeOfErrno(
    code: Int,
    valueOf: (Errno) -> Int?,
): IoCause = Errno.entries.firstOrNull { valueOf(it) == code }?.cause ?: IoCause.Other
