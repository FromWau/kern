package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The rules every [Writer] call keeps, one test per call and rule: a bad name touches nothing, a link on the way that
 * leads out is [FileError.Escapes], a link at the name is never written through, and a replaced root is
 * [FileError.FolderReplaced].
 */
class WriterContractTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val outside = (dir / "outside").also { createDirectoriesRaw(it) }
    private val theirs = (outside / "theirs.txt").writeRaw("theirs")
    private val source = (dir / "source.txt").writeRaw("copied")
    private val links = TestLinks()
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()
    private val outsideReal = outside.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private val writeText = { w: Writer, name: String -> w.writeText(name, "new") }
    private val writeBytes = { w: Writer, name: String -> w.writeBytes(name, "new".encodeToByteArray()) }
    private val copyFrom = { w: Writer, name: String -> w.copyFrom(source, name) }
    private val readText = { w: Writer, name: String -> w.readText(name) }
    private val readBytes = { w: Writer, name: String -> w.readBytes(name) }
    private val readPrefix = { w: Writer, name: String -> w.readPrefix(name, maxBytes = 3) }
    private val info = { w: Writer, name: String -> w.info(name) }
    private val append = { w: Writer, name: String ->
        w.openAppend(name).flatMap { file -> file.use { it.appendLine("line") } }
    }
    private val delete = { w: Writer, name: String -> w.delete(name) }
    private val deleteRecursively = { w: Writer, name: String -> w.deleteRecursively(name) }
    private val moveFrom = { w: Writer, name: String -> w.move(name, "moved") }
    private val moveTo = { w: Writer, name: String -> w.move("present", name) }
    private val createDirectories = { w: Writer, name: String -> w.createDirectories(name) }
    private val switchSymlink = { w: Writer, name: String -> w.switchSymlink(name, Path("elsewhere")) }
    private val createDirectoryAtomically = { w: Writer, name: String ->
        w.createDirectoryAtomically(name) { stage -> stage.writeText("inner", "x") }
    }
    private val tryLock = { w: Writer, name: String -> w.tryLock(name).map { it.close() } }
    private val withLock = { w: Writer, name: String -> w.withLock(name) { "held" } }
    private val childWriter = { w: Writer, name: String -> w.writer(name) }
    private val childPrivateWriter = { w: Writer, name: String -> w.privateWriter(name) }

    /** Runs [op] with a name that climbs out of the root, and checks it answers so with nothing touched. */
    private fun assertBadNameTouchesNothing(op: (Writer, String) -> Result<Any?, FileError>) {
        val before = dir.tree()
        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)

        assertEquals(Err(FileError.InvalidRelativePath(root, "../x", problem)), op(writer, "../x"))
        assertEquals(before, dir.tree())
    }

    /** Runs [op] on `sub/f`, `sub` a link leading out of the root, and checks it is Escapes with nothing outside. */
    private fun assertLinkOutOnTheWayEscapes(op: (Writer, String) -> Result<Any?, FileError>) {
        links.make(rootPath / "sub", Path("../outside"))
        (outside / "f").writeRaw("theirs")
        (rootPath / "present").writeRaw("present")
        val before = outside.tree()

        assertEquals(escapes("sub/f"), op(writer, "sub/f"))
        assertEquals(before, outside.tree())
    }

    /** Escapes for [name] reached through a link leading to the outside folder. */
    private fun escapes(name: String) = Err(FileError.Escapes(root / name, boundary = root, resolved = outsideReal))

    /** Escapes for a read of `x`, the link to the file outside the root, resolved to that file. */
    private val escapesAtName = Err(FileError.Escapes(root / "x", boundary = root, resolved = theirs))

    /** Makes `x` a link to a file outside the root, runs [op] on it, and checks that file was never touched. */
    private fun withLinkAtName(op: (Writer, String) -> Result<Any?, FileError>): Result<Any?, FileError> {
        links.make(rootPath / "x", theirs)
        (rootPath / "present").writeRaw("present")
        val done = op(writer, "x")
        assertEquals("theirs", theirs.readRaw())
        assertEquals(listOf(theirs), SystemFileSystem.list(outside))
        return done
    }

    /** Checks `x` is still the link to the file outside the root. */
    private fun assertLinkKept() {
        assertEquals(Ok(theirs), (rootPath / "x").readSymlink())
    }

    /** NotRegularFile naming the link `x`, which a write refuses unless asked to replace it. */
    private val refusedLink = Err(FileError.NotRegularFile(root / "x", FileType.Symlink))

    /** Checks the link `x` was replaced by a regular file. */
    private fun assertLinkReplacedByFile() {
        assertFalse(isSymlink(rootPath / "x"))
        assertEquals(Ok(FileType.Regular), (rootPath / "x").fileType())
    }

    /** Moves the root away and makes a new folder at its path, then checks [op] is FolderReplaced and does nothing. */
    private fun assertReplacedRootDoesNothing(op: (Writer, String) -> Result<Any?, FileError>) {
        SystemFileSystem.atomicMove(rootPath, dir / "moved")
        createDirectoriesRaw(rootPath)
        val before = dir.tree()

        assertEquals(Err(FileError.FolderReplaced(root)), op(writer, "f"))
        assertEquals(before, dir.tree())
    }

    // --- writeText

    @Test
    fun `writeText writes a file inside`() {
        assertEquals(Ok(WriteWay.Renamed), writer.writeText("a/b.txt", "new").way())
        assertEquals("new", (rootPath / "a" / "b.txt").readRaw())
    }

    @Test
    fun `writeText of a bad name touches nothing`() = assertBadNameTouchesNothing(writeText)

    @Test
    fun `writeText through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(writeText)

    @Test
    fun `writeText refuses a link at the name and touches nothing`() {
        assertEquals(refusedLink, withLinkAtName(writeText))
        assertLinkKept()
    }

    @Test
    fun `writeText with replaceLink replaces a link at the name`() {
        val replaced = withLinkAtName { w, name -> w.writeText(name, "new", replaceLink = true) }
        assertEquals(WriteWay.Renamed, assertIs<Written>(replaced.assertSuccess()).way)
        assertLinkReplacedByFile()
        assertEquals("new", (rootPath / "x").readRaw())
    }

    @Test
    fun `writeText in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(writeText)

    // --- writeBytes

    @Test
    fun `writeBytes writes a file inside`() {
        assertEquals(Ok(WriteWay.Renamed), writer.writeBytes("b.bin", "new".encodeToByteArray()).way())
        assertEquals("new", (rootPath / "b.bin").readRaw())
    }

    @Test
    fun `writeBytes of a bad name touches nothing`() = assertBadNameTouchesNothing(writeBytes)

    @Test
    fun `writeBytes through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(writeBytes)

    @Test
    fun `writeBytes refuses a link at the name and touches nothing`() {
        assertEquals(refusedLink, withLinkAtName(writeBytes))
        assertLinkKept()
    }

    @Test
    fun `writeBytes with replaceLink replaces a link at the name`() {
        val replaced = withLinkAtName { w, name -> w.writeBytes(name, "new".encodeToByteArray(), replaceLink = true) }
        assertEquals(WriteWay.Renamed, assertIs<Written>(replaced.assertSuccess()).way)
        assertLinkReplacedByFile()
    }

    @Test
    fun `writeBytes in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(writeBytes)

    // --- copyFrom

    @Test
    fun `copyFrom copies a file inside`() {
        assertEquals(Ok(WriteWay.Renamed), writer.copyFrom(source, "c/copy.txt").way())
        assertEquals("copied", (rootPath / "c" / "copy.txt").readRaw())
    }

    @Test
    fun `copyFrom of a bad name touches nothing`() = assertBadNameTouchesNothing(copyFrom)

    @Test
    fun `copyFrom through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(copyFrom)

    @Test
    fun `copyFrom refuses a link at the name and touches nothing`() {
        assertEquals(refusedLink, withLinkAtName(copyFrom))
        assertLinkKept()
    }

    @Test
    fun `copyFrom with replaceLink replaces a link at the name`() {
        val replaced = withLinkAtName { w, name -> w.copyFrom(source, name, replaceLink = true) }
        assertEquals(WriteWay.Renamed, assertIs<Written>(replaced.assertSuccess()).way)
        assertLinkReplacedByFile()
        assertEquals("copied", (rootPath / "x").readRaw())
    }

    @Test
    fun `copyFrom in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(copyFrom)

    // --- readText

    @Test
    fun `readText reads a file inside`() {
        createDirectoriesRaw(rootPath / "a")
        (rootPath / "a" / "r.txt").writeRaw("read")

        assertEquals(Ok("read"), writer.readText("a/r.txt"))
    }

    @Test
    fun `readText of a bad name touches nothing`() = assertBadNameTouchesNothing(readText)

    @Test
    fun `readText through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(readText)

    @Test
    fun `readText of a link at the name leading out is Escapes`() {
        assertEquals(escapesAtName, withLinkAtName(readText))
    }

    @Test
    fun `readText in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(readText)

    // --- readBytes

    @Test
    fun `readBytes reads a file inside`() {
        (rootPath / "r.bin").writeRaw("read")

        assertEquals("read", writer.readBytes("r.bin").assertSuccess().decodeToString())
    }

    @Test
    fun `readBytes of a bad name touches nothing`() = assertBadNameTouchesNothing(readBytes)

    @Test
    fun `readBytes through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(readBytes)

    @Test
    fun `readBytes of a link at the name leading out is Escapes`() {
        assertEquals(escapesAtName, withLinkAtName(readBytes))
    }

    @Test
    fun `readBytes in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(readBytes)

    // --- readPrefix

    @Test
    fun `readPrefix reads the start of a file inside`() {
        (rootPath / "r.txt").writeRaw("readable")

        assertEquals("rea", writer.readPrefix("r.txt", maxBytes = 3).assertSuccess().decodeToString())
    }

    @Test
    fun `readPrefix of a bad name touches nothing`() = assertBadNameTouchesNothing(readPrefix)

    @Test
    fun `readPrefix through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(readPrefix)

    @Test
    fun `readPrefix of a link at the name leading out is Escapes`() {
        assertEquals(escapesAtName, withLinkAtName(readPrefix))
    }

    @Test
    fun `readPrefix in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(readPrefix)

    // --- info

    @Test
    fun `info looks up a file inside`() {
        (rootPath / "i.txt").writeRaw("four")

        assertEquals(4L, writer.info("i.txt").assertSuccess().size)
    }

    @Test
    fun `info of a bad name touches nothing`() = assertBadNameTouchesNothing(info)

    @Test
    fun `info through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(info)

    @Test
    fun `info of a link at the name leading out is Escapes`() {
        assertEquals(escapesAtName, withLinkAtName(info))
    }

    @Test
    fun `info in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(info)

    // --- openAppend

    @Test
    fun `openAppend appends to a file inside`() {
        assertEquals(Ok(Unit), append(writer, "logs/app.log"))
        assertEquals(Ok(Unit), append(writer, "logs/app.log"))
        assertEquals("line\nline\n", (rootPath / "logs" / "app.log").readRaw())
    }

    @Test
    fun `openAppend of a bad name touches nothing`() = assertBadNameTouchesNothing(append)

    @Test
    fun `openAppend through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(append)

    @Test
    fun `openAppend refuses a link at the name and touches nothing`() {
        assertEquals(refusedLink, withLinkAtName(append))
        assertLinkKept()
    }

    @Test
    fun `openAppend with replaceLink replaces a link at the name`() {
        val appended = withLinkAtName { w, name ->
            w.openAppend(name, replaceLink = true).flatMap { file -> file.use { it.appendLine("line") } }
        }

        assertEquals(Ok(Unit), appended)
        assertLinkReplacedByFile()
        assertEquals("line\n", (rootPath / "x").readRaw())
    }

    @Test
    fun `openAppend in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(append)

    // --- delete

    @Test
    fun `delete removes a file inside`() {
        (rootPath / "d.txt").writeRaw("gone")

        assertEquals(Ok(Unit), writer.delete("d.txt"))
        assertFalse(SystemFileSystem.exists(rootPath / "d.txt"))
    }

    @Test
    fun `delete of a bad name touches nothing`() = assertBadNameTouchesNothing(delete)

    @Test
    fun `delete through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(delete)

    @Test
    fun `delete removes a link at the name and not its target`() {
        assertEquals(Ok(Unit), withLinkAtName(delete))
        assertEquals(listOf(rootPath / "present"), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `delete in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(delete)

    // --- deleteRecursively

    @Test
    fun `deleteRecursively removes a tree inside`() {
        createDirectoriesRaw(rootPath / "t" / "u")
        (rootPath / "t" / "u" / "f.txt").writeRaw("gone")

        assertEquals(Ok(Unit), writer.deleteRecursively("t"))
        assertFalse(SystemFileSystem.exists(rootPath / "t"))
    }

    @Test
    fun `deleteRecursively of a bad name touches nothing`() = assertBadNameTouchesNothing(deleteRecursively)

    @Test
    fun `deleteRecursively through a link leading out is Escapes`() =
        assertLinkOutOnTheWayEscapes(deleteRecursively)

    @Test
    fun `deleteRecursively removes a link at the name and not its target`() {
        assertEquals(Ok(Unit), withLinkAtName(deleteRecursively))
        assertEquals(listOf(rootPath / "present"), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `deleteRecursively in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(deleteRecursively)

    // --- move

    @Test
    fun `move moves a file inside into new folders`() {
        (rootPath / "m.txt").writeRaw("moved")

        assertEquals(Ok(Unit), writer.move("m.txt", "n/o/m.txt"))
        assertEquals("moved", (rootPath / "n" / "o" / "m.txt").readRaw())
        assertFalse(SystemFileSystem.exists(rootPath / "m.txt"))
    }

    @Test
    fun `move of a bad name touches nothing`() = assertBadNameTouchesNothing(moveFrom)

    @Test
    fun `move to a bad name touches nothing`() = assertBadNameTouchesNothing(moveTo)

    @Test
    fun `move from a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(moveFrom)

    @Test
    fun `move to a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(moveTo)

    @Test
    fun `move moves a link at the name as a link`() {
        assertEquals(Ok(Unit), withLinkAtName(moveFrom))
        assertEquals(theirs, readLink(rootPath / "moved"))
    }

    @Test
    fun `move replaces a link at the target name`() {
        assertEquals(Ok(Unit), withLinkAtName(moveTo))
        assertLinkReplacedByFile()
        assertEquals("present", (rootPath / "x").readRaw())
    }

    @Test
    fun `move in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(moveFrom)

    // --- createDirectories

    @Test
    fun `createDirectories makes folders inside`() {
        assertEquals(Ok(Unit), writer.createDirectories("p/q"))
        assertEquals(Ok(FileType.Directory), (rootPath / "p" / "q").fileType())
    }

    @Test
    fun `createDirectories of a bad name touches nothing`() = assertBadNameTouchesNothing(createDirectories)

    @Test
    fun `createDirectories through a link leading out is Escapes`() =
        assertLinkOutOnTheWayEscapes(createDirectories)

    @Test
    fun `createDirectories of a link at the name leading out is Escapes`() {
        links.make(rootPath / "x", outside)

        assertEquals(escapes("x"), writer.createDirectories("x"))
        assertEquals(listOf(theirs), SystemFileSystem.list(outside))
    }

    @Test
    fun `createDirectories in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(createDirectories)

    // --- switchSymlink

    @Test
    fun `switchSymlink makes a link inside`() {
        assertEquals(Ok(Unit), writer.switchSymlink("l/current", Path("release-1")))
        assertEquals(Path("release-1"), readLink(rootPath / "l" / "current"))
    }

    @Test
    fun `switchSymlink of a new link that fails to move into place is WriteFailed at it and removes it`() {
        var staged: Path? = null

        val switched = writer.switchSymlinkWith("current", Path("release-1")) { level, from, _ ->
            staged = level.pathOf(from)
            throw IOException("refused")
        }

        val at = FailedAt.Temp(checkNotNull(staged))
        assertEquals(Err(writeFailed(root / "current", IOException("refused"), at = at)), switched)
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `switchSymlink of a bad name touches nothing`() = assertBadNameTouchesNothing(switchSymlink)

    @Test
    fun `switchSymlink through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(switchSymlink)

    @Test
    fun `switchSymlink switches a link at the name and leaves its old target`() {
        assertEquals(Ok(Unit), withLinkAtName(switchSymlink))
        assertEquals(Path("elsewhere"), readLink(rootPath / "x"))
    }

    @Test
    fun `switchSymlink in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(switchSymlink)

    // --- createDirectoryAtomically

    @Test
    fun `createDirectoryAtomically builds a folder inside`() {
        assertEquals(Ok(Ok(WriteWay.Renamed)), createDirectoryAtomically(writer, "s/built").map { it.way() })
        assertEquals("x", (rootPath / "s" / "built" / "inner").readRaw())
    }

    @Test
    fun `createDirectoryAtomically of a bad name touches nothing`() =
        assertBadNameTouchesNothing(createDirectoryAtomically)

    @Test
    fun `createDirectoryAtomically through a link leading out is Escapes`() =
        assertLinkOutOnTheWayEscapes(createDirectoryAtomically)

    @Test
    fun `createDirectoryAtomically refuses a link at the name and leaves its target`() {
        val refused = withLinkAtName(createDirectoryAtomically)

        assertEquals(Err(FileError.AlreadyExists(root / "x", FileType.Symlink)), refused)
    }

    @Test
    fun `createDirectoryAtomically in a replaced root is FolderReplaced`() =
        assertReplacedRootDoesNothing(createDirectoryAtomically)

    // --- tryLock

    @Test
    fun `tryLock locks a file inside`() {
        val lock = writer.tryLock("locks/.app.lock").assertSuccess()
        lock.use { writer.tryLock("locks/.app.lock").assertError<FileError.LockBusy>() }
        assertEquals(Ok(FileType.Regular), (rootPath / "locks" / ".app.lock").fileType())
    }

    @Test
    fun `tryLock of a bad name touches nothing`() = assertBadNameTouchesNothing(tryLock)

    @Test
    fun `tryLock through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(tryLock)

    @Test
    fun `tryLock replaces a link at the name`() {
        assertEquals(Ok(Unit), withLinkAtName(tryLock))
        assertLinkReplacedByFile()
    }

    @Test
    fun `tryLock in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(tryLock)

    // --- withLock

    @Test
    fun `withLock runs the block holding a lock inside`() {
        val ran = writer.withLock(".app.lock") {
            writer.tryLock(".app.lock").assertError<FileError.LockBusy>()
            "held"
        }

        assertEquals(Ok("held"), ran)
    }

    @Test
    fun `withLock of a bad name touches nothing`() = assertBadNameTouchesNothing(withLock)

    @Test
    fun `withLock through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(withLock)

    @Test
    fun `withLock replaces a link at the name`() {
        assertEquals(Ok("held"), withLinkAtName(withLock))
        assertLinkReplacedByFile()
    }

    @Test
    fun `withLock in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(withLock)

    // --- writer

    @Test
    fun `writer of a name makes a child writer inside`() {
        val child = writer.writer("w/child").assertSuccess()

        assertEquals(root / "w" / "child", child.root)
        assertEquals(Ok(WriteWay.Renamed), child.writeText("f", "new").way())
        assertEquals("new", (rootPath / "w" / "child" / "f").readRaw())
    }

    @Test
    fun `writer of a bad name touches nothing`() = assertBadNameTouchesNothing(childWriter)

    @Test
    fun `writer through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(childWriter)

    @Test
    fun `writer of a link at the name leading out is Escapes`() {
        links.make(rootPath / "x", outside)

        assertEquals(escapes("x"), writer.writer("x"))
    }

    @Test
    fun `writer in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(childWriter)

    // --- privateWriter

    @Test
    fun `privateWriter of a name makes a private child writer inside`() {
        val child = writer.privateWriter("pw").assertSuccess()

        assertEquals(root / "pw", child.root)
        assertEquals(true, child.isPrivate)
        (rootPath / "pw").assertPermissions(0b111_000_000)
    }

    @Test
    fun `privateWriter of a bad name touches nothing`() = assertBadNameTouchesNothing(childPrivateWriter)

    @Test
    fun `privateWriter through a link leading out is Escapes`() = assertLinkOutOnTheWayEscapes(childPrivateWriter)

    @Test
    fun `privateWriter of a link at the name leading out is Escapes`() {
        links.make(rootPath / "x", outside)

        assertEquals(escapes("x"), writer.privateWriter("x"))
    }

    @Test
    fun `privateWriter in a replaced root is FolderReplaced`() = assertReplacedRootDoesNothing(childPrivateWriter)

    // --- leftovers

    @Test
    fun `leftovers in a replaced root is FolderReplaced`() =
        assertReplacedRootDoesNothing { w, _ -> w.leftovers(depth = Int.MAX_VALUE) }
}
