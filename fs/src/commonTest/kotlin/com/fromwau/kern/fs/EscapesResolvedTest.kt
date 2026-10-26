package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

/** Where [FileError.Escapes] says a link leading out of a [Writer]'s root leads. */
class EscapesResolvedTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val outside = (dir / "outside").also { createDirectoriesRaw(it) }
    private val hostname = (outside / "hostname").writeRaw("host")
    private val links = TestLinks()
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun escapes(
        name: String,
        resolved: Path,
    ) = Err(FileError.Escapes(root / name, boundary = root, resolved = resolved))

    @Test
    fun `a link at the name to a folder outside resolves to that folder`() {
        links.make(rootPath / "current", outside)

        assertEquals(escapes("current", outside), writer.readText("current"))
    }

    @Test
    fun `a link at the name to a file outside resolves to that file`() {
        links.make(rootPath / "host", hostname)

        assertEquals(escapes("host", hostname), writer.readText("host"))
    }

    @Test
    fun `a relative link at the name resolves to its target read from the link's folder by name`() {
        links.make(rootPath / "host", Path("../outside/./hostname"))

        assertEquals(escapes("host", Path(checkNotNull(root.parent), "outside", "hostname")), writer.readText("host"))
    }

    @Test
    fun `a chain at the name whose last link leads out resolves to where that link leads`() {
        links.make(rootPath / "a", Path("b"))
        links.make(rootPath / "b", hostname)

        assertEquals(escapes("a", hostname), writer.readText("a"))
    }

    @Test
    fun `a link on the way resolves to the folder it names`() {
        links.make(rootPath / "out", outside)

        assertEquals(escapes("out/hostname", outside.realPath().assertSuccess()), writer.readText("out/hostname"))
    }

    @Test
    fun `a switched link whose target leads out resolves to that target read from the link's folder by name`() {
        createDirectoriesRaw(rootPath / "lib")

        val switched = writer.switchSymlink("lib/up", Path("../.."))

        assertEquals(escapes("lib/up", checkNotNull(root.parent)), switched)
    }

    @Test
    fun `a link on the way that cannot be resolved resolves to its target read by name`() {
        links.make(rootPath / "up", Path("../outside/hostname/.."))

        assertEquals(escapes("up/f", Path(checkNotNull(root.parent), "outside")), writer.realName("up/f"))
    }
}
