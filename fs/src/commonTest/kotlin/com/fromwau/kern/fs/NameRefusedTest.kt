package com.fromwau.kern.fs

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class NameRefusedTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val writer = rootPath.writer().assertSuccess()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun assertNothingMade() = assertEquals(emptyList(), SystemFileSystem.list(rootPath))

    @Test
    fun `a Writer refusing a name that is no relative path below its root answers NameRefused`() {
        val refused = writer.writeText("../a.txt", "a").assertError<FileError.NameRefused>()

        assertIs<FileError.InvalidRelativePath>(refused)
        assertNothingMade()
    }

    @Test
    fun `a Writer refusing a name a link takes out of its root answers NameRefused`() {
        createDirectoriesRaw(dir / "outside")
        links.make(rootPath / "out", Path("../outside"))

        val refused = writer.writeText("out/a.txt", "a").assertError<FileError.NameRefused>()

        assertIs<FileError.Escapes>(refused)
        assertEquals(emptyList(), SystemFileSystem.list(dir / "outside"))
    }

    @Test
    fun `a Writer refusing a link pointed at itself answers NameRefused`() {
        val refused = writer.switchSymlink("current", Path("current")).assertError<FileError.NameRefused>()

        assertIs<FileError.InvalidName>(refused)
        assertNothingMade()
    }

    @Test
    fun `a Writer refusing a name too long to stage answers NameRefused`() {
        val name = "b".repeat(MAX_STAGED_NAME_BYTES + 1)

        val refused = writer.switchSymlink(name, Path("x")).assertError<FileError.NameRefused>()

        assertIs<FileError.NameTooLong>(refused)
        assertNothingMade()
    }
}
