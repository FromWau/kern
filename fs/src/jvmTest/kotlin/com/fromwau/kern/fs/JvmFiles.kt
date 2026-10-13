package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Takes away read and traverse permission, so nothing under [path] can be reached. */
internal fun shutOut(path: Path) {
    File(path.toString()).apply {
        setReadable(false)
        setExecutable(false)
    }
}

internal fun restore(path: Path) {
    File(path.toString()).apply {
        setReadable(true)
        setExecutable(true)
    }
}

/** Whether this runner can still look the path up, which root can whatever the permissions say. */
internal fun canLookUp(path: Path): Boolean = runCatching {
    Files.readAttributes(Paths.get(path.toString()), BasicFileAttributes::class.java)
}.isSuccess

/** Whether this runner can still read the directory, which root can whatever the permissions say. */
internal fun canList(path: Path): Boolean = File(path.toString()).list() != null

/** Makes this path a second name for [target]'s file. */
internal fun Path.hardLinkTo(target: Path): Path = also {
    Files.createLink(Paths.get(toString()), Paths.get(target.toString()))
}

/** Which file this name stands for, so a test can tell a file replaced by rename from one written in place. */
internal fun Path.inode(): Any = Files.getAttribute(Paths.get(toString()), "unix:ino")

/** A group this process's user belongs to besides its own, or null when it has only that one or `id` is missing. */
internal fun otherGroupOrNull(): Int? = runCatching {
    val own = ProcessBuilder("id", "-g")
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()
    ProcessBuilder("id", "-G")
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .split(' ')
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it != own }
        ?.toInt()
}.getOrNull()
