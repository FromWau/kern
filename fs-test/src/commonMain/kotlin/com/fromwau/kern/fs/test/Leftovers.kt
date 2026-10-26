package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.Leftover
import com.fromwau.kern.fs.LeftoverNames
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlinx.io.files.Path

/** Each kind of [Leftover] [leaveLeftover] can leave beside a file. */
public enum class LeftoverKind {
    /** A [Leftover.Temp]: `.<name>.<id>.tmp`, a file holding the content. */
    Temp,

    /** A [Leftover.UnnamedTemp]: `.<id>.tmp`, a file holding the content. */
    UnnamedTemp,

    /** A [Leftover.Backup]: `.<name>.<id>.bak`, a file holding the content. */
    Backup,

    /** A [Leftover.PartialBackup]: `.<name>.<id>.bak.part`, a file holding the content. */
    PartialBackup,

    /** A [Leftover.StagedFolder]: `.<name>.<id>.dir`, an empty folder. */
    StagedFolder,

    /** A [Leftover.StagedLink]: `.<name>.<id>.link`, a symlink to the content, or to the file's name without one. */
    StagedLink,
}

/**
 * Leaves a real leftover of [kind] beside this file, named as kern:fs's own writes name it with a new write id, as a
 * write, folder build or link switch killed partway would leave it, and answers its path. The file itself need not
 * exist, and its folder is created when missing. At a symlink, a write's temp or backup goes beside the file the link
 * leads to, as a write through the link leaves it, and a staged link or folder beside the link itself.
 *
 * ```kotlin
 * val backup = (dir / "app.toml").leaveLeftover(LeftoverKind.Backup, content = "port = 1")
 * assertEquals(backup, dir.leftoversIn().assertSuccess().found.single().copy)
 * ```
 *
 * Throws [IllegalStateException] when it cannot be made, since a test cannot go on without it.
 *
 * @param content what a file kind holds; the target of a [LeftoverKind.StagedLink], which holds this file's name when
 *   it is empty. A [LeftoverKind.StagedFolder] is made empty.
 */
@IgnorableReturnValue
@OptIn(InternalKernApi::class)
public fun Path.leaveLeftover(
    kind: LeftoverKind,
    content: String = "",
): Path {
    val (copy, made) = when (kind) {
        LeftoverKind.Temp -> LeftoverNames.temp(this).madeHolding(content)
        LeftoverKind.UnnamedTemp -> LeftoverNames.unnamedTemp(this).madeHolding(content)
        LeftoverKind.Backup -> LeftoverNames.backup(this).madeHolding(content)
        LeftoverKind.PartialBackup -> LeftoverNames.partialBackup(this).madeHolding(content)
        LeftoverKind.StagedFolder -> LeftoverNames.stagedFolder(this).let { it to it.createDirectories() }
        LeftoverKind.StagedLink -> LeftoverNames.stagedLink(this).madeLinkingTo(Path(content.ifEmpty { name }))
    }
    check(made is Result.Success) { "no $kind left beside $this: $made" }
    return copy
}

/** This path, and how writing [content] to it went. */
private fun Path.madeHolding(content: String): Pair<Path, EmptyResult<FileError>> = this to writeText(content).map { }

/** This path, and how making it a symlink to [target], as [symlinkTo] makes one, went. */
private fun Path.madeLinkingTo(target: Path): Pair<Path, EmptyResult<FileError>> = this to symlinkTo(target)
