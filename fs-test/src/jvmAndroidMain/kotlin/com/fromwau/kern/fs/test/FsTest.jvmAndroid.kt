@file:OptIn(InternalKernApi::class)

package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.alreadyExists
import com.fromwau.kern.fs.isSymlink
import com.fromwau.kern.fs.of
import com.fromwau.kern.fs.pathError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal actual fun environmentVariable(name: String): String? = System.getenv(name)

internal actual fun platformTempRoot(): Path =
    Path(checkNotNull(System.getProperty("java.io.tmpdir")) { "java.io.tmpdir is not set" })

public actual fun Path.hardLinkTo(target: Path): EmptyResult<FileError> {
    (pathError() ?: target.pathError())?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    return made(this) { Files.createLink(Paths.get(toString()), Paths.get(target.toString())) }
}

public actual fun Path.chmod(bits: Int): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    val file = Paths.get(toString())
    val views = file.fileSystem.supportedFileAttributeViews()
    return try {
        when {
            "unix" in views -> Files.setAttribute(file, "unix:mode", bits)
            "posix" in views -> Files.setPosixFilePermissions(file, permissionsOf(bits))
            else -> Files
                .getFileAttributeView(file, DosFileAttributeView::class.java)
                .setReadOnly(bits and OWNER_WRITE == 0)
        }
        Ok(Unit)
    } catch (_: NoSuchFileException) {
        Err(FileError.NotFound(this))
    } catch (e: Exception) {
        Err(FileError.WriteFailed(this, IoCause.of(e), e))
    }
}

public actual fun Path.mkfifo(): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    // The command says why it failed only in words, so what is already there is asked of kern:fs first.
    if (isSymlink() is Result.Success) return Err(alreadyExists())
    parentsMade()?.let { return Err(it) }
    return made(this) {
        val command = ProcessBuilder("mkfifo", toString()).redirectErrorStream(true).start()
        val output = command.inputStream.readBytes().decodeToString()
        val exitCode = command.waitFor()
        if (exitCode != 0) throw IOException("mkfifo exited with $exitCode: $output")
    }
}

/** Runs [make], which creates [path], answering what it threw as fs answers a failed creation. */
private inline fun made(
    path: Path,
    make: () -> Unit,
): EmptyResult<FileError> = try {
    make()
    Ok(Unit)
} catch (_: FileAlreadyExistsException) {
    Err(path.alreadyExists())
} catch (e: Exception) {
    Err(FileError.WriteFailed(path, IoCause.of(e), e))
}

// PosixFilePermission lists the nine bits from the owner's read down to everyone's execute.
private fun permissionsOf(bits: Int): Set<PosixFilePermission> =
    PosixFilePermission.entries.filterIndexedTo(mutableSetOf()) { index, _ -> bits and (OWNER_READ shr index) != 0 }

private const val OWNER_READ = 0b100_000_000
private const val OWNER_WRITE = 0b010_000_000
