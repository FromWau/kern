package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlin.jvm.JvmInline
import kotlinx.io.files.Path

/**
 * Who may read, write and run what this path reaches, following symlinks. No owner or group comes with it, so the
 * bits alone cannot tell whether another user may write the file; [info] says whether you own it.
 *
 * ```kotlin
 * when (val mode = secrets.permissions().getOrElse { return warn(it) }) {
 *     null, Permissions(0b110_000_000) -> Unit
 *     else -> println("$secrets is $mode rather than rw-------")
 * }
 * ```
 *
 * @return the permissions, or null on Windows, native or JVM, where a file's mode says at most whether it is
 *   read-only and who may read it comes from its folder's access rules instead; [FileError.NotFound] when nothing
 *   is there, or [FileError.Inaccessible] when it may not be looked up.
 */
public fun Path.permissions(): Result<Permissions?, FileError> = info().map { it.permissions }

/**
 * The permission bits of a mode: nine for owner, group and others, and setuid, setgid and sticky above them, so
 * `644` is `Permissions(0b110_100_100)`. [toString] spells them as `ls -l` does, `rw-r--r--`.
 */
@JvmInline
public value class Permissions(public val bits: Int) {
    /** Whether the owner may read. */
    public val ownerRead: Boolean get() = bits and (READ shl OWNER_SHIFT) != 0

    /** Whether the owner may write. */
    public val ownerWrite: Boolean get() = bits and (WRITE shl OWNER_SHIFT) != 0

    /** Whether the owner may run the file, or enter the folder. */
    public val ownerExecute: Boolean get() = bits and (EXECUTE shl OWNER_SHIFT) != 0

    /** Whether the owner, the group or everyone else may run the file, or enter the folder. */
    public val anyExecute: Boolean
        get() = bits and ((EXECUTE shl OWNER_SHIFT) or (EXECUTE shl GROUP_SHIFT) or (EXECUTE shl OTHERS_SHIFT)) != 0

    /** Whether the group or everyone else may write, so someone besides the owner may change the file. */
    public val othersMayWrite: Boolean get() = bits and ((WRITE shl GROUP_SHIFT) or (WRITE shl OTHERS_SHIFT)) != 0

    /** Whether the group or everyone else may read, so someone besides the owner may see what the file holds. */
    public val othersMayRead: Boolean get() = bits and ((READ shl GROUP_SHIFT) or (READ shl OTHERS_SHIFT)) != 0

    override fun toString(): String =
        rights(shift = OWNER_SHIFT, special = SETUID_BIT, mark = 's') +
            rights(shift = GROUP_SHIFT, special = SETGID_BIT, mark = 's') +
            rights(shift = OTHERS_SHIFT, special = STICKY_BIT, mark = 't')

    /** `rwx` for the three bits at [shift], with [mark] for `x` when [special] is set, upper case without `x`. */
    private fun rights(
        shift: Int,
        special: Int,
        mark: Char,
    ): String {
        val read = if (bits and (READ shl shift) != 0) 'r' else '-'
        val write = if (bits and (WRITE shl shift) != 0) 'w' else '-'
        val runs = bits and (EXECUTE shl shift) != 0
        val run = when {
            bits and special == 0 -> if (runs) 'x' else '-'
            runs -> mark
            else -> mark.uppercaseChar()
        }
        return "$read$write$run"
    }
}

/** These permission bits with an execute bit for each class that may read, so `644` becomes `755`. */
internal fun Int.withExecuteForReaders(): Int {
    var bits = this
    for (shift in listOf(OWNER_SHIFT, GROUP_SHIFT, OTHERS_SHIFT)) {
        if (this and (READ shl shift) != 0) bits = bits or (EXECUTE shl shift)
    }
    return bits
}

private const val OWNER_SHIFT = 6
private const val GROUP_SHIFT = 3
private const val OTHERS_SHIFT = 0
private const val READ = 0b100
private const val WRITE = 0b010
private const val EXECUTE = 0b001
private const val SETUID_BIT = 0b100_000_000_000
private const val SETGID_BIT = 0b010_000_000_000
private const val STICKY_BIT = 0b001_000_000_000

/**
 * [path]'s own permission bits, following symlinks, to carry onto a copy: on Windows native the read-only flag alone,
 * and null where the platform has none; throws when unreadable.
 */
internal expect fun permissionBits(path: Path): Int?

/** Sets [path]'s own permission bits to [bits], throwing why it could not; a platform with none does nothing. */
internal expect fun setFilePermissions(
    path: Path,
    bits: Int,
)

/**
 * Makes the folder at [path] one only its owner may list, enter or change, where the platform has permissions that
 * say so; Windows native leaves it as it is.
 */
internal expect fun restrictToOwner(path: Path)

/**
 * Hands [to] to [from]'s group, so a renamed copy keeps who may share the file. Answers false when that could
 * not be done, and true where it was already so or a platform has no groups to keep.
 */
internal expect fun copyGroup(
    from: Path,
    to: Path,
): Boolean

/** The bits of a mode that say who may do what: nine for owner, group and other, and the three above them. */
internal const val PERMISSION_BITS = 0b111_111_111_111

/** The bits of a mode that say what the file's group may do. */
internal const val GROUP_BITS = 0b000_000_111_000

/** `0666`: the mode a new file is asked for, which the umask narrows as it does for any new file. */
internal const val NEW_FILE_MODE = 0b110_110_110

/** `0600`: a file only its owner may read and write. */
internal const val OWNER_READ_WRITE = 0b110_000_000

/** `0700`: a folder only its owner may list, enter and change. */
internal const val OWNER_ONLY_FOLDER = 0b111_000_000

/** `0777`: the mode a new folder is asked for, which the umask narrows as it does for `mkdir -p`. */
internal const val FOLDER_MODE = 0b111_111_111

/** `0644`: the mode a shared [Writer] asks for a new file, which the umask may narrow but never widen. */
internal const val SHARED_FILE_MODE = 0b110_100_100

/**
 * `0755`: the mode a shared [Writer] asks for a new folder, which the umask may narrow but never widen, and the widest
 * it gives a file.
 */
internal const val SHARED_FOLDER_MODE = 0b111_101_101

/** `0644`: the mode a new lock file is asked for, which the umask may narrow but never widen. */
internal const val LOCK_FILE_MODE = 0b110_100_100

/** The mode a folder is made with: [OWNER_ONLY_FOLDER] with [ownerOnly], otherwise [FOLDER_MODE]. */
internal fun pathFolderMode(ownerOnly: Boolean): Int = if (ownerOnly) OWNER_ONLY_FOLDER else FOLDER_MODE
