package com.fromwau.kern.dirs

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.uuid.Uuid

// Linux native's SystemTemporaryDirectory is empty and relative when neither TMPDIR nor TMP is set.
private val tempBase: Path = SystemTemporaryDirectory.takeIf { it.isAbsolute } ?: Path("/tmp")

// One folder per test process, so two concurrent runs, such as jvmTest beside linuxX64Test, never share files. It
// sits directly in the temp directory, so the last cleanup leaves nothing behind.
private val runDir: Path = (tempBase / "kern-dirs-${Uuid.random()}").also {
    SystemFileSystem.createDirectories(it)
}

/** A fresh, empty directory under this test process's run folder. Delete it with [deleteTree] in an `@AfterTest`. */
internal fun newTempDir(): Path {
    val dir = runDir / Uuid.random().toString()
    SystemFileSystem.createDirectories(dir)
    return dir
}

/** Writes [content] with kotlinx-io directly, so a test's setup never depends on the code under test. */
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
 * Deletes a tree of regular files and directories, then removes the run folder once it holds no more test
 * directories. Remove any symlink before this runs: it follows links, and kotlinx-io's JVM delete skips a
 * link whose target is already gone.
 */
internal fun Path.deleteTree() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        SystemFileSystem.list(this).forEach { it.deleteTree() }
    }
    SystemFileSystem.delete(this, mustExist = false)
    if (parent == runDir && SystemFileSystem.list(runDir).isEmpty()) {
        SystemFileSystem.delete(runDir, mustExist = false)
    }
}
