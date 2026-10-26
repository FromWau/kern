package com.fromwau.kern.fs

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileOpensJvmTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a folder opened for writing through kern's own open is NotRegularFile`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)

        val failure = assertFailsWith<NotRegularFileFailure> { openFileSink(folder, append = false).close() }

        assertEquals(FileType.Directory, failure.type)
    }
}
