package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class FileInfoTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @Test
    fun `info of a file matches what the single-purpose calls answer`() {
        val file = (dir / "a.txt").writeRaw("abc")

        val info = file.info().assertSuccess()

        assertEquals(FileType.Regular, info.type)
        assertEquals(3L, info.size)
        assertEquals(file.fileSize().assertSuccess(), info.size)
        assertEquals(file.modifiedAt().assertSuccess(), info.modifiedAt)
        assertEquals(file.permissions().assertSuccess(), info.permissions)
    }

    @Test
    fun `a lookup that found nothing is NotFound also when the file is there once it is checked again`() {
        val file = (dir / "a.txt").writeRaw("a")
        val missing = OsCallFailure(IoCause.NotFound, file, "No such file or directory")

        assertEquals(Err(FileError.NotFound(file)), file.inaccessibleUnlessGone(missing))
    }

    @Test
    fun `info of a folder names it a Directory as fileType does`() {
        val folder = (dir / "sub").also { createDirectoriesRaw(it) }

        val info = folder.info().assertSuccess()

        assertEquals(FileType.Directory, info.type)
        assertEquals(folder.fileType().assertSuccess(), info.type)
        assertEquals(folder.modifiedAt().assertSuccess(), info.modifiedAt)
    }

    @Test
    fun `info follows a symlink to the file it leads to`() {
        val file = (dir / "a.txt").writeRaw("abcd")
        val link = links.make(dir / "link", Path("a.txt"))

        assertEquals(file.info(), link.info())
    }

    @Test
    fun `info of a missing path or a dangling link is NotFound`() {
        val missing = dir / "missing"
        val dangling = links.make(dir / "dangling", Path("missing"))

        assertEquals(Err(FileError.NotFound(missing)), missing.info())
        assertEquals(Err(FileError.NotFound(dangling)), dangling.info())
        assertEquals(Err(FileError.NotFound(Path(""))), Path("").info())
    }
}
