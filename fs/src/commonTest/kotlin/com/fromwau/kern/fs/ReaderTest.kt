package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class ReaderTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val root = rootPath.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `reader of a missing folder is NotFound and makes nothing`() {
        assertEquals(Err(FileError.NotFound(dir / "a" / "b")), (dir / "a" / "b").reader())
        assertEquals(Err(FileError.NotFound(dir / "a" / "b")), (dir / "a" / "b").privateReader())
        assertFalse(SystemFileSystem.exists(dir / "a"))
    }

    @Test
    fun `reader of a file is NotADirectory`() {
        val file = (dir / "file").writeRaw("x")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.reader())
    }

    @Test
    fun `a reader reads what is below its root by the names a writer takes`() {
        createDirectoriesRaw(rootPath / "notes")
        (rootPath / "notes" / "today.md").writeRaw("hello")
        (rootPath / "current").createSymlink(Path("notes/today.md")).assertSuccess()

        val reader = rootPath.reader().assertSuccess()

        assertEquals(root, reader.root)
        assertEquals(Ok("hello"), reader.readText("notes/today.md"))
        assertEquals("hel", reader.readPrefix("current", 3).assertSuccess().decodeToString())
        assertEquals(Ok(listOf("current", "notes")), reader.list())
        assertEquals(Ok(listOf("notes/today.md")), reader.list("notes"))
        assertEquals(Ok(Path("notes/today.md")), reader.readSymlink("current"))
        assertEquals(FileType.Symlink, reader.info("current", followLinks = false).assertSuccess().type)
        assertEquals(5L, reader.info("current").assertSuccess().size)
    }

    @Test
    fun `a reader can not be used as a Writer`() {
        assertFalse(rootPath.reader().assertSuccess() is Writer)
    }

    @Test
    fun `a writer is a Reader`() {
        fun names(reader: Reader) = reader.list()
        (rootPath / "a").writeRaw("a")

        assertEquals(Ok(listOf("a")), names(rootPath.writer().assertSuccess()))
    }

    @Test
    fun `a writer's reader reads below its root and can not be used as a Writer`() {
        (rootPath / "a").writeRaw("a")
        val writer = rootPath.writer().assertSuccess()

        val reader = writer.reader

        assertFalse(reader is Writer)
        assertEquals(root, reader.root)
        assertEquals(Ok("a"), reader.readText("a"))
        assertEquals("Reader(root=$root, isPrivate=false)", reader.toString())
    }

    @Test
    fun `realName of a name with no link on the way is that name`() {
        createDirectoriesRaw(rootPath / "notes")
        (rootPath / "notes" / "today.md").writeRaw("t")

        assertEquals(Ok("notes/today.md"), rootPath.reader().assertSuccess().realName("notes/today.md"))
    }

    @Test
    fun `realName follows a relative link to the name it leads to`() {
        createDirectoriesRaw(rootPath / "releases" / "43")
        (rootPath / "current").createSymlink(Path("releases/43")).assertSuccess()

        assertEquals(Ok("releases/43"), rootPath.reader().assertSuccess().realName("current"))
    }

    @Test
    fun `realName follows an absolute link inside the root`() {
        createDirectoriesRaw(rootPath / "notes")
        (rootPath / "notes" / "today.md").writeRaw("t")
        (rootPath / "today").createSymlink(root / "notes" / "today.md").assertSuccess()

        assertEquals(Ok("notes/today.md"), rootPath.reader().assertSuccess().realName("today"))
    }

    @Test
    fun `realName follows a chain of links and a link on the way`() {
        createDirectoriesRaw(rootPath / "releases" / "43")
        (rootPath / "releases" / "43" / "app").writeRaw("app")
        (rootPath / "current").createSymlink(Path("releases/43")).assertSuccess()
        (rootPath / "live").createSymlink(Path("current")).assertSuccess()
        (rootPath / "app").createSymlink(Path("live/app")).assertSuccess()

        assertEquals(Ok("releases/43/app"), rootPath.reader().assertSuccess().realName("app"))
    }

    @Test
    fun `realName of a link to dot is the name of the folder the link is in`() {
        createDirectoriesRaw(rootPath / "notes")
        (rootPath / "notes" / "here").createSymlink(Path(".")).assertSuccess()

        assertEquals(Ok("notes"), rootPath.reader().assertSuccess().realName("notes/here"))
    }

    @Test
    fun `realName of a link to a folder and dot dot is the name of the folder the link is in`() {
        createDirectoriesRaw(rootPath / "notes" / "inner")
        (rootPath / "notes" / "back").createSymlink(Path("inner/..")).assertSuccess()

        assertEquals(Ok("notes"), rootPath.reader().assertSuccess().realName("notes/back"))
    }

    @Test
    fun `realName of a link to dot at the root is null for the root`() {
        (rootPath / "self").createSymlink(Path(".")).assertSuccess()

        assertEquals<Result<String?, FileError>>(Ok(null), rootPath.reader().assertSuccess().realName("self"))
    }

    @Test
    fun `realName of a link to dot dot in a folder at the root is null for the root`() {
        createDirectoriesRaw(rootPath / "a")
        (rootPath / "a" / "up").createSymlink(Path("..")).assertSuccess()

        assertEquals<Result<String?, FileError>>(Ok(null), rootPath.reader().assertSuccess().realName("a/up"))
    }

    @Test
    fun `realName of a link leading out of the root is Escapes`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        (outside / "secret").writeRaw("s")
        (rootPath / "out").createSymlink(outside / "secret").assertSuccess()

        rootPath.reader().assertSuccess().realName("out").assertError<FileError.Escapes>()
    }

    @Test
    fun `realName of a link whose target is missing is NotFound`() {
        (rootPath / "dangling").createSymlink(Path("missing")).assertSuccess()

        assertEquals(Err(FileError.NotFound(root / "dangling")), rootPath.reader().assertSuccess().realName("dangling"))
    }

    @Test
    fun `a reader refuses a link leading out of its root as Escapes`() {
        val outside = (dir / "outside").also { createDirectoriesRaw(it) }
        (outside / "secret").writeRaw("s")
        (rootPath / "out").createSymlink(outside).assertSuccess()

        rootPath.reader().assertSuccess().readText("out/secret").assertError<FileError.Escapes>()
    }

    @Test
    fun `a reader refuses a name that leaves its root`() {
        rootPath.reader().assertSuccess().readText("../x").assertError<FileError.InvalidRelativePath>()
    }

    @Test
    fun `a reader whose root moved away is FolderReplaced`() {
        val reader = rootPath.reader().assertSuccess()
        SystemFileSystem.atomicMove(rootPath, dir / "moved")

        assertEquals(Err(FileError.FolderReplaced(root)), reader.list())
    }

    @Test
    fun `privateReader refuses a root others may read`() {
        rootPath.chmod(0b111_101_101).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayRead, rootPath)),
            rootPath.privateReader(),
        )
    }

    @Test
    fun `privateReader refuses a file others may read and reads one only its owner may`() {
        rootPath.chmod(OWNER_ONLY_FOLDER).assertSuccess()
        (rootPath / "open").writeRaw("o").chmod(0b110_100_100).assertSuccess()
        (rootPath / "token").writeRaw("t").chmod(0b110_000_000).assertSuccess()
        val reader = rootPath.privateReader().assertSuccess()
        if (!hasPermissions()) return
        val info = (rootPath / "open").info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root / "open", info, RefusedBecause.OthersMayRead, root / "open")),
            reader.readText("open"),
        )
        assertEquals(Ok("t"), reader.readText("token"))
    }

    @Test
    fun `a private reader names its root and says it is private`() {
        rootPath.chmod(OWNER_ONLY_FOLDER).assertSuccess()

        assertEquals("Reader(root=$root, isPrivate=true)", rootPath.privateReader().assertSuccess().toString())
    }
}
