package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.SystemFileSystem
import platform.posix.geteuid

class StagedFoldersLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = dir.deleteTree()

    @Test
    fun `an empty folder made between the check and the rename is AlreadyExists and stays empty`() {
        val target = dir / "pkg"
        val stage = target.makeStage().assertSuccess()
        (stage.path / "a.txt").writeRaw("mine")

        // Removed as createDirectoryAtomically removes it, whatever the rename did.
        val placed = try {
            target.placeStage(stage.path, afterCheck = { createDirectoriesRaw(target) })
        } finally {
            stage.remove()
        }

        assertEquals(Err(FileError.AlreadyExists(target, FileType.Directory)), placed)
        assertEquals(emptyList(), SystemFileSystem.list(target))
        assertEquals(listOf(target), SystemFileSystem.list(dir))
    }

    @Test
    fun `a stage folder that cannot be entered once made is removed again`() {
        // Root enters a folder whatever its mode, so the stage would be set up.
        if (geteuid() == 0u) return
        val writer = dir.writer().assertSuccess()

        // A umask that takes every bit makes the stage `000`, which this user cannot open.
        val made = withUmask(0b111_111_111) { writer.createDirectoryAtomically("pkg") { } }

        made.assertError<FileError.WriteFailed>()
        assertEquals(emptyList(), SystemFileSystem.list(dir))
    }
}
