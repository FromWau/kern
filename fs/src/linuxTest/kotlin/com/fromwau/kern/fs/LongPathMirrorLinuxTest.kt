@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close

class LongPathMirrorLinuxTest {
    private val dir = newTempDir()
    private val src = dir / "src"
    private val dst = dir / "dst"
    private val name = "d".repeat(200)

    @AfterTest
    fun cleanUp() {
        removeDeepTree()
        dir.deleteTree()
    }

    /** Makes [LEVELS] folders named [name] in [src], one in the other, and `deep.txt` in the last, each by its folder. */
    private fun makeDeepTree() {
        createDirectoriesRaw(src)
        var folder = openAt(WORKING_DIRECTORY, src.toString(), O_RDONLY or O_DIRECTORY, 0)
        repeat(LEVELS) {
            check(makeFolderAt(folder, name, 0b111_101_101) == 0)
            val next = openAt(folder, name, O_RDONLY or O_DIRECTORY, 0)
            close(folder)
            folder = next
        }
        close(openAt(folder, "deep.txt", O_WRONLY or O_CREAT, 0b110_100_100))
        close(folder)
    }

    /** Removes what [makeDeepTree] made, each by its folder, since the deepest lie past what a path can name. */
    private fun removeDeepTree() {
        val folders = mutableListOf(openAt(WORKING_DIRECTORY, src.toString(), O_RDONLY or O_DIRECTORY, 0))
        if (folders.last() < 0) return
        while (true) {
            val next = openAt(folders.last(), name, O_RDONLY or O_DIRECTORY, 0)
            if (next < 0) break
            folders += next
        }
        val _ = removeAt(folders.last(), "deep.txt", isFolder = false)
        for (level in folders.indices.reversed()) {
            if (level < folders.lastIndex) {
                val _ = removeAt(folders[level], name, isFolder = true)
            }
            close(folders[level])
        }
    }

    @Test
    fun `a mirror of a source deeper than PATH_MAX fails the entry past it and makes the folders above it`() {
        makeDeepTree()

        val report = src.mirrorTo(dst.writer().assertSuccess()).assertSuccess()

        val failure = report.failures.single()
        assertEquals(IoCause.Other, assertIs<FileError.Inaccessible>(failure.error).cause)
        assertTrue(failure.path.toString().encodeToByteArray().size >= PATH_MAX_BYTES, failure.path.toString())
        assertTrue(SystemFileSystem.metadataOrNull(dst / checkNotNull(failure.relative.parent))?.isDirectory == true)
    }

    private companion object {
        /** Enough folders of 200 bytes each to take a path well past [PATH_MAX_BYTES]. */
        const val LEVELS = 25

        const val PATH_MAX_BYTES = 4096
    }
}
