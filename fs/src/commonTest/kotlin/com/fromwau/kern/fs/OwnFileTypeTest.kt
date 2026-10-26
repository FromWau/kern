package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class OwnFileTypeTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @Test
    fun `fileType without following links names a link to a file or a folder Symlink`() {
        val file = (dir / "a.txt").writeRaw("a")
        val toFile = links.make(dir / "to-file", file)
        val toFolder = links.make(dir / "to-folder", dir)

        assertEquals(Ok(FileType.Symlink), toFile.fileType(followLinks = false))
        assertEquals(Ok(FileType.Symlink), toFolder.fileType(followLinks = false))
        assertEquals(Ok(FileType.Regular), toFile.fileType())
        assertEquals(Ok(FileType.Directory), toFolder.fileType())
    }

    @Test
    fun `fileType without following links names a dangling link and a loop Symlink`() {
        val dangling = links.make(dir / "dangling", dir / "missing")
        val loop = links.make(dir / "self", Path("self"))

        assertEquals(Ok(FileType.Symlink), dangling.fileType(followLinks = false))
        assertEquals(Ok(FileType.Symlink), loop.fileType(followLinks = false))
        assertEquals(Err(FileError.NotFound(dangling)), dangling.fileType())
        assertEquals(IoCause.Loop, loop.fileType().assertError<FileError.Inaccessible>().cause)
    }

    @Test
    fun `fileType without following links answers what is there for a file or a folder`() {
        val file = (dir / "a.txt").writeRaw("a")
        createDirectoriesRaw(dir / "sub")

        assertEquals(Ok(FileType.Regular), file.fileType(followLinks = false))
        assertEquals(Ok(FileType.Directory), (dir / "sub").fileType(followLinks = false))
    }

    @Test
    fun `fileType without following links is NotFound where nothing is or through a file`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").fileType(followLinks = false))
        assertEquals(Err(FileError.NotFound(file / "x")), (file / "x").fileType(followLinks = false))
    }

    @Test
    fun `fileType without following links follows a link among the folders above`() {
        createDirectoriesRaw(dir / "real")
        (dir / "real" / "a.txt").writeRaw("a")
        links.make(dir / "linked", dir / "real")

        assertEquals(Ok(FileType.Regular), (dir / "linked" / "a.txt").fileType(followLinks = false))
    }
}
