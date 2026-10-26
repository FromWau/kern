package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.FileSystems
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Whether java.nio reads unix attributes such as `unix:uid` here, which Windows does not. */
internal val hasUnixView: Boolean = "unix" in FileSystems.getDefault().supportedFileAttributeViews()

/** Whether java.nio reads and sets posix permissions here, which Windows does not. */
internal val hasPosixView: Boolean = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

internal actual fun restrictToOwner(path: Path) = setFilePermissions(path, OWNER_ONLY_FOLDER)

/** The permissions the mode [bits] grant, of the nine for owner, group and other. */
internal fun posixPermissionsOf(bits: Int): Set<PosixFilePermission> =
    PosixFilePermission.entries.filterTo(mutableSetOf()) { it.bit and bits != 0 }

/** A file or folder created with the mode [bits], narrowed by the umask as anything new is. */
internal fun createdWithMode(bits: Int): FileAttribute<Set<PosixFilePermission>> =
    PosixFilePermissions.asFileAttribute(posixPermissionsOf(bits))

// The entries run from OWNER_READ to OTHERS_EXECUTE, which is the order of the nine bits from the top down.
internal val PosixFilePermission.bit: Int
    get() = 1 shl (PosixFilePermission.entries.size - 1 - ordinal)
