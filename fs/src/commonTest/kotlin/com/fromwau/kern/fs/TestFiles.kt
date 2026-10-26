package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

// One folder per test process, so two concurrent runs, such as jvmTest beside linuxX64Test, never share files. It
// sits directly in the temp directory, so the last cleanup leaves nothing behind.
private val runDir: Path = scratchDir()

/** A fresh, empty directory under this test process's run folder. Delete it with [deleteTree] in an `@AfterTest`. */
internal fun newTempDir(): Path = (runDir / Uuid.random().toString()).also(::createDirectoriesRaw)

/**
 * Makes [path] and the missing folders on the way with kotlinx-io directly, each one it makes [mode] whatever the
 * umask, since `002` would leave them `775`, a folder others may write that kern refuses. A private writer's root
 * takes [OWNER_ONLY_FOLDER].
 */
internal fun createDirectoriesRaw(
    path: Path,
    mode: Int = 0b111_101_101,
) {
    val missing = generateSequence(path) { it.parent }
        .takeWhile { !SystemFileSystem.exists(it) }
        .toList()
    SystemFileSystem.createDirectories(path)
    missing.forEach { it.chmod(mode).assertSuccess() }
}

/** Writes [content] with kotlinx-io directly, so a test's setup never depends on the code under test. */
@IgnorableReturnValue
internal fun Path.writeRaw(content: String): Path = also { path ->
    SystemFileSystem
        .sink(path)
        .buffered()
        .use { it.writeString(content) }
}

internal fun Path.readRaw(): String = SystemFileSystem
    .source(this)
    .buffered()
    .use { it.readString() }

/**
 * Deletes a tree, each symlink in it as a link, then removes the run folder once it holds no more test directories.
 */
internal fun Path.deleteTree() {
    if (isSymlink(this)) {
        // kotlinx-io looks a path up before removing it, following links, so it cannot remove a dangling one.
        deleteLink(this)?.let { throw it }
    } else {
        if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
            SystemFileSystem.list(this).forEach { it.deleteTree() }
        }
        SystemFileSystem.delete(this, mustExist = false)
    }
    if (parent == runDir && SystemFileSystem.list(runDir).isEmpty()) {
        SystemFileSystem.delete(runDir, mustExist = false)
    }
}

/** [WriteFolder.copyFile] of [from] to a new [to] beside it, as an in-place write backs a file up. */
internal fun copyFile(
    from: Path,
    to: Path,
    ownerOnly: Boolean = false,
): HeldFile = PathFolder(from.parent).copyFile(from.name, to.name, ownerOnly)

/** A write id spelled the way a real one is, so a name a test puts beside a file reads as a write's backup. */
internal const val A_WRITE_ID = "0192d3a4-5b6c-7d8e-9f01-23456789abcd"

/** When the write [A_WRITE_ID] names started: the millisecond its first 48 bits count. */
internal val A_WRITE_START: Instant = Instant.fromEpochMilliseconds(0x0192d3a45b6c)

/** The copies of this file's leftovers of kind [K], for a test about one kind. */
internal inline fun <reified K : Leftover> Path.leftoverCopies(): Result<List<Path>, FileError> =
    leftovers().map { found -> found.filterIsInstance<K>().map { it.copy } }

/** What a walk yielded with every skipped entry left out, each file as its path, for a test about files alone. */
internal fun Sequence<Result<WalkEntry, WalkFailure>>.walkedFiles(): List<Result<Path, WalkFailure>> =
    mapNotNull { step ->
        when (step) {
            is Result.Success -> (step.value as? WalkEntry.File)?.let { Ok(it.path) }
            is Result.Error -> step
        }
    }.toList()

/** The [WalkEntry.File] a walk yields for [path] at [relative], timed as [info] reads it now. */
internal fun walkedFile(
    path: Path,
    relative: Path,
    size: Long,
    viaLink: Boolean,
): WalkEntry.File = WalkEntry.File(path, relative, size, path.info().assertSuccess().modifiedAt, viaLink)

/** How this write replaced its file, for a test about the way alone; [Copied.Unchanged] reads as null. */
internal fun Result<Copied, FileError>.way(): Result<WriteWay?, FileError> = map { (it as? Written)?.way }

/** Which file this name stands for, so a test can tell a file replaced by rename from one written in place. */
internal fun Path.fileKey(): Any = checkNotNull(fileKeyOrNull(this)) { "no file key for $this" }

/** Asserts this path's permissions are [bits] where the platform has any; Windows has none. */
internal fun Path.assertPermissions(bits: Int) {
    permissions().assertSuccess()?.let { assertEquals(Permissions(bits), it) }
}

/**
 * The mode a shared writer gives a folder under this process's umask, `755` narrowed by it, read from a folder made
 * at [probe] with kotlinx-io directly. Windows keeps no modes, so there it is `755`.
 */
internal fun sharedFolderBits(probe: Path): Int {
    SystemFileSystem.createDirectories(probe)
    return (probe.permissions().assertSuccess()?.bits ?: 0b111_111_111) and 0b111_101_101
}

/** [sharedFolderBits] for a file: `644` narrowed by the umask, read from a file written at [probe]. */
internal fun sharedFileBits(probe: Path): Int {
    probe.writeRaw("")
    return (probe.permissions().assertSuccess()?.bits ?: 0b110_110_110) and 0b110_100_100
}

/**
 * Runs [block] with this path at mode [bits], `700` again afterwards so the cleanup can remove it. [block] is skipped
 * where [stillWorks] answers true under the mode, as for root or on Windows, which leaves nothing to observe.
 */
internal fun Path.withMode(
    bits: Int,
    stillWorks: () -> Boolean,
    block: () -> Unit,
) {
    chmod(bits).assertSuccess()
    try {
        if (stillWorks()) return
        block()
    } finally {
        chmod(0b111_000_000).assertSuccess()
    }
}

/**
 * Runs [block] with this folder at mode `000`, open again afterwards so the cleanup can remove it. [block] is skipped
 * where the folder can still be listed, as for root or on Windows, which leaves nothing to observe.
 */
internal fun Path.whileUnlistable(block: () -> Unit) = withMode(0, stillWorks = { list() is Result.Success }, block)

/** Whether the file system the tests run in keeps POSIX permissions, which Windows does not. */
internal fun hasPermissions(): Boolean = runDir.permissions().assertSuccess() != null

/** The symlinks a test made, removed before its folders so clearing a tree never follows one back into it. */
internal class TestLinks {
    private val made = mutableListOf<Path>()

    /** Makes [link] a symlink to [target]. */
    @IgnorableReturnValue
    fun make(
        link: Path,
        target: Path,
    ): Path {
        link.createSymlink(target).assertSuccess()
        made += link
        return link
    }

    /** Removes each link that is still one, since a test may have put a file in its place. */
    fun removeAll() {
        made.forEach { if (it.isSymlink() == Ok(true)) it.delete().assertSuccess() }
    }
}
