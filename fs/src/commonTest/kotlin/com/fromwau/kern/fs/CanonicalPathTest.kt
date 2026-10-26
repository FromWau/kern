package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class CanonicalPathTest {
    private val dir = newTempDir()
    private val real = dir.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `an existing path is its real path`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(file.realPath(), file.canonicalPath())
    }

    @Test
    fun `a missing leaf is appended to the real path of its folder`() {
        assertEquals(Ok(real / "missing.txt"), (dir / "missing.txt").canonicalPath())
    }

    @Test
    fun `a missing chain drops dot and applies dot dot by name`() {
        val path = Path(dir, "gone", ".", "deeper", "..", "kept", "a.txt")

        assertEquals(Ok(Path(real, "gone", "kept", "a.txt")), path.canonicalPath())
    }

    @Test
    fun `a relative path is resolved against the working directory`() {
        val name = "kern-missing-${dir.name}"

        assertEquals(Ok(Path(".").realPath().assertSuccess() / name), Path(name).canonicalPath())
    }

    @Test
    fun `a missing rest below a regular file is NotFound as a lookup of it is`() {
        val file = (dir / "a.txt").writeRaw("a")
        val below = file / "x"

        assertEquals(Err(FileError.NotFound(below)), below.canonicalPath())
        assertEquals(Err(FileError.NotFound(below)), below.fileType())
    }

    @Test
    fun `an empty path is NotFound`() {
        assertEquals(Err(FileError.NotFound(Path(""))), Path("").canonicalPath())
    }

    @Test
    fun `the answer for a missing folder is the one it gets once the folder exists`() {
        val path = dir / "later" / "a.txt"
        val before = path.canonicalPath()
        createDirectoriesRaw(dir / "later")

        assertEquals(before, path.canonicalPath())
    }
}
