package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlinx.io.files.Path

/**
 * A read-only [Reader] on this folder, for a command that only looks: it reads as a [Writer] on the folder
 * reads, under the same rules, and never makes anything.
 *
 * ```kotlin
 * val data = dirs.data.reader().getOrElse { return warn(it) }
 * val notes = data.list("notes").getOrElse { return warn(it) }
 * ```
 *
 * @return the reader, its [Reader.root] this folder's real path; [FileError.NotFound] naming this path for a
 *   missing folder; [FileError.NotADirectory] when something else is there; or what resolving the folder answers.
 */
public fun Path.reader(): Result<Reader, FileError> = readOnly(rootRule = null)

/**
 * A read-only [Reader] on this folder that reads as a [privateWriter] reads: it refuses a root another user owns
 * or its group or everyone else may write or read, and reads only a file nobody else may change or read. It makes
 * nothing, as [reader] says.
 *
 * @return the reader; [FileError.Refused] naming the folder with [FileError.Refused.why] saying why it is not yours
 *   alone; otherwise what [reader] answers.
 */
public fun Path.privateReader(): Result<Reader, FileError> = readOnly(RootRule.Private)

private fun Path.readOnly(rootRule: RootRule?): Result<Reader, FileError> =
    newWriter(rootRule, create = false).map { it.reader }

/**
 * The reads of one folder, its [root], so a name you hand it can never reach outside: a [Writer] is one, and [reader]
 * and [Writer.reader] make one that cannot write. Each call keeps the rules [Writer] lists for names, the root and
 * symlinks.
 */
public interface Reader {
    /** The real path of the folder this reads in, with no symlink in it. */
    public val root: Path

    /**
     * The whole file [name] as UTF-8 text; bytes that are not valid UTF-8 read as U+FFFD.
     *
     * @param maxBytes the largest file read; a larger one is [FileError.TooLarge]. Null reads it whole.
     * @param trusted refuses a file, the root, or a folder on the way, that someone besides you may change: another
     *   user owns it, or its group or everyone else may write it. For a child writer that includes each folder up to
     *   the root of the writer it was made from. That is [FileError.Refused] naming it, with [FileError.Refused.why]
     *   saying which, and nothing is read. Each call judges them anew. A private writer or reader reads this way
     *   always, and refuses a file its group or everyone else may read too, as [RefusedBecause.OthersMayRead].
     * @param streams reads a FIFO or a character device too, to its end, as `streams` reads it for [Path.readBytes]:
     *   it may block until a writer comes and closes it, and [maxBytes] is checked as it is read.
     * @return the text; [FileError.NotFound] when nothing is at [name]; [FileError.NotRegularFile] for a folder or
     *   anything else that is no file and not let through by [streams]; [FileError.Escapes] when a link leads out; or
     *   [FileError.Inaccessible].
     */
    public fun readText(
        name: String,
        maxBytes: Long? = null,
        trusted: Boolean = false,
        streams: Boolean = false,
    ): Result<String, FileError>

    /** [readText] as raw bytes, with the same rules and answers. */
    public fun readBytes(
        name: String,
        maxBytes: Long? = null,
        trusted: Boolean = false,
        streams: Boolean = false,
    ): Result<ByteArray, FileError>

    /**
     * Up to [maxBytes] from the start of the file [name], all of it when it is shorter, read as [readText] reads.
     *
     * @param maxBytes how many bytes to read at most; zero or less reads none.
     */
    public fun readPrefix(
        name: String,
        maxBytes: Long,
    ): Result<ByteArray, FileError>

    /**
     * The target the symlink [name] holds, as it was written: a relative one is read from the link's folder. The link
     * itself is looked at, so its target may lie anywhere, or nowhere. [realName] answers where it leads below the
     * root.
     *
     * @return the target; [FileError.NotASymlink] naming [name] when something else is there; [FileError.NotFound]
     *   when nothing is; [FileError.Escapes] when a link on the way leads out; or [FileError.Inaccessible].
     */
    public fun readSymlink(name: String): Result<Path, FileError>

    /**
     * The names in the folder [folder], or in the root when it is null, in name order, each a name below the root that
     * the other calls take: `pkgs/a` for `a` in `pkgs`, and a bare `a` at the root. Each entry is listed as it is, so
     * a symlink is listed and not followed, and a leftover is listed too. A symlink on the way, or at [folder] itself,
     * is followed under the rules.
     *
     * @return the names; [FileError.NotFound] when nothing is at [folder]; [FileError.NotADirectory] when it is no
     *   folder; [FileError.Escapes] when a link leads out; or [FileError.Inaccessible] when it may not be listed.
     */
    public fun list(folder: String? = null): Result<List<String>, FileError>

    /**
     * What is at [name], as [info] describes it.
     *
     * @param followLinks true describes what a symlink at [name] leads to, followed as [readText] follows it. False
     *   describes the link itself, its type [FileType.Symlink], wherever it leads.
     * @return the info; [FileError.NotFound] when nothing is there, [FileError.Escapes] when a link leads out, or
     *   [FileError.Inaccessible].
     */
    public fun info(
        name: String,
        followLinks: Boolean = true,
    ): Result<FileInfo, FileError>

    /**
     * The name below the root of what is really at [name], each symlink on the way and at [name] itself followed as
     * [readText] follows it: `releases/43` for `current` when `current -> releases/43`. A [name] with no link on the
     * way comes back as its names joined by `/`. Null stands for the root itself, as it does for [list], and comes
     * back only when the links end there, such as for `self -> .` at the root or `a/up -> ..`.
     *
     * @return the name, or null for the root; [FileError.Escapes] when a link leads out; [FileError.NotFound] when
     *   nothing is at the end, such as for a link whose target is missing; [FileError.NotADirectory] when a name on
     *   the way is no folder; [FileError.Refused] for a private writer or reader when the root or a folder on the way
     *   is one someone besides you may change, as `trusted` refuses it for [readText]; or [FileError.Inaccessible].
     */
    public fun realName(name: String): Result<String?, FileError>
}

/** The reads of [writer] and nothing else, so code handed it cannot write. */
internal class ReadOnlyReader(private val writer: Writer) : Reader by writer {
    override fun toString(): String = "Reader(root=$root, isPrivate=${writer.isPrivate})"
}
