package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.LeftoverKind
import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.fs.test.leaveLeftover
import com.fromwau.kern.fs.test.setModifiedAt
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class WriterTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val outside = (dir / "outside").also { createDirectoriesRaw(it) }
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()
    private val outsideReal = outside.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        dir.chmod(0b111_000_000).assertSuccess()
        if (SystemFileSystem.exists(rootPath)) rootPath.chmod(0b111_000_000).assertSuccess()
        dir.deleteTree()
    }

    // --- modification time

    @Test
    fun `writeText with modifiedAt gives the new file that time by rename and in place`() {
        val time = Instant.parse("2001-02-03T04:05:06Z")

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("a.txt", "a", modifiedAt = time).way())
        assertEquals(time, (rootPath / "a.txt").info().assertSuccess().modifiedAt)
        assertEquals(Ok(WriteWay.InPlace), writer.writeText("a.txt", "b", inPlace = true, modifiedAt = time).way())
        assertEquals(time, (rootPath / "a.txt").info().assertSuccess().modifiedAt)
        assertEquals("b", (rootPath / "a.txt").readRaw())
    }

    @Test
    fun `writeBytes with modifiedAt gives the new file that time`() {
        val time = Instant.parse("2001-02-03T04:05:06Z")

        writer.writeBytes("a.bin", byteArrayOf(1, 2), modifiedAt = time).assertSuccess()

        assertEquals(time, (rootPath / "a.bin").info().assertSuccess().modifiedAt)
    }

    // --- making a writer

    @Test
    fun `writer makes the missing levels of its root`() {
        val made = (dir / "a" / "b").writer().assertSuccess()

        assertEquals(Ok(FileType.Directory), (dir / "a" / "b").fileType())
        assertEquals((dir / "a" / "b").realPath().assertSuccess(), made.root)
        assertFalse(made.isPrivate)
    }

    @Test
    fun `writer answers the real path of its root`() {
        (dir / "link").createSymlink(rootPath).assertSuccess()

        assertEquals(root, (dir / "link").writer().assertSuccess().root)
    }

    @Test
    fun `writer on a dangling link with a relative target makes that target and its missing folders`() {
        val shared = sharedFolderBits(dir / "probe")
        val link = (dir / "link").also { it.createSymlink(Path("made/sub/data")).assertSuccess() }

        val made = link.writer().assertSuccess()

        assertEquals((dir / "made" / "sub" / "data").realPath().assertSuccess(), made.root)
        assertEquals(Ok(true), link.isSymlink())
        (dir / "made").assertPermissions(shared)
        (dir / "made" / "sub" / "data").assertPermissions(shared)
    }

    @Test
    fun `writer on a dangling link with an absolute target makes that target and its missing folders`() {
        val target = dir / "far" / "data"
        val link = (dir / "link").also { it.createSymlink(target).assertSuccess() }

        val made = link.writer().assertSuccess()

        assertEquals(target.realPath().assertSuccess(), made.root)
        assertEquals(Ok(true), link.isSymlink())
        made.writeText("a.txt", "a").assertSuccess()
        assertEquals("a", (target / "a.txt").readRaw())
    }

    @Test
    fun `privateWriter on a dangling link makes its target for its owner alone`() {
        val shared = sharedFolderBits(dir / "probe")
        val link = (dir / "link").also { it.createSymlink(Path("made/data")).assertSuccess() }

        val made = link.privateWriter().assertSuccess()

        assertTrue(made.isPrivate)
        assertEquals((dir / "made" / "data").realPath().assertSuccess(), made.root)
        (dir / "made").assertPermissions(shared)
        (dir / "made" / "data").assertPermissions(0b111_000_000)
    }

    @Test
    fun `writer with create false and reader on a dangling link are NotFound and make nothing`() {
        val link = (dir / "link").also { it.createSymlink(Path("made/data")).assertSuccess() }

        assertEquals(Err(FileError.NotFound(link)), link.writer(create = false))
        assertEquals(Err(FileError.NotFound(link)), link.privateWriter(create = false))
        assertEquals(Err(FileError.NotFound(link)), link.reader())
        assertFalse(SystemFileSystem.exists(dir / "made"))
        assertEquals(Ok(true), link.isSymlink())
    }

    @Test
    fun `writer on a chain of dangling links makes the target of the last and keeps every link`() {
        val link = (dir / "link").also { it.createSymlink(Path("next")).assertSuccess() }
        (dir / "next").createSymlink(Path("made/data")).assertSuccess()

        val made = link.writer().assertSuccess()

        assertEquals((dir / "made" / "data").realPath().assertSuccess(), made.root)
        assertEquals(Ok(true), link.isSymlink())
        assertEquals(Ok(true), (dir / "next").isSymlink())
    }

    @Test
    fun `writer on a dangling link whose target lies below another dangling link is WriteFailed naming that link`() {
        (dir / "gone").createSymlink(Path("nowhere")).assertSuccess()
        val link = (dir / "link").also { it.createSymlink(Path("gone/data")).assertSuccess() }

        assertEquals(Err(FileError.WriteFailed(dir / "gone", IoCause.Other, null)), link.writer())
        assertFalse(SystemFileSystem.exists(dir / "nowhere"))
    }

    @Test
    fun `writer on a link loop is Inaccessible as a Loop naming the link`() {
        val link = (dir / "loop-a").also { it.createSymlink(Path("loop-b")).assertSuccess() }
        (dir / "loop-b").createSymlink(Path("loop-a")).assertSuccess()

        assertEquals(Err(FileError.Inaccessible(link, IoCause.Loop, null)), link.writer())
    }

    @Test
    fun `writer on a dangling link whose target lies below a file is NotADirectory naming the file`() {
        val file = (dir / "file").writeRaw("x")
        val link = (dir / "link").also { it.createSymlink(Path("file/data")).assertSuccess() }

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), link.writer())
    }

    @Test
    fun `writer of a file is NotADirectory`() {
        val file = (dir / "file").writeRaw("x")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.writer())
    }

    @Test
    fun `writer with create false makes nothing and is NotFound for a missing folder`() {
        assertEquals(Err(FileError.NotFound(dir / "a" / "b")), (dir / "a" / "b").writer(create = false))
        assertEquals(Err(FileError.NotFound(dir / "a" / "b")), (dir / "a" / "b").privateWriter(create = false))
        assertFalse(SystemFileSystem.exists(dir / "a"))
    }

    @Test
    fun `writer with create false of a file is NotADirectory`() {
        val file = (dir / "file").writeRaw("x")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.writer(create = false))
        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.privateWriter(create = false))
    }

    @Test
    fun `writer with create false takes a folder already there`() {
        assertEquals(root, rootPath.writer(create = false).assertSuccess().root)
        rootPath.chmod(0b111_000_000).assertSuccess()
        assertTrue(rootPath.privateWriter(create = false).assertSuccess().isPrivate)
    }

    @Test
    fun `privateWriter makes its root for its owner alone and a missing level above it as a shared writer would`() {
        val shared = sharedFolderBits(dir / "probe")

        val made = (dir / "p" / "q").privateWriter().assertSuccess()

        assertTrue(made.isPrivate)
        (dir / "p").assertPermissions(shared)
        (dir / "p" / "q").assertPermissions(0b111_000_000)
    }

    @Test
    fun `privateWriter refuses a root anybody may write`() {
        rootPath.chmod(0b111_111_111).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayWrite, rootPath)),
            rootPath.privateWriter(),
        )
    }

    @Test
    fun `privateWriter refuses a root its group may write`() {
        rootPath.chmod(0b111_111_101).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayWrite, rootPath)),
            rootPath.privateWriter(),
        )
    }

    @Test
    fun `privateWriter refuses a root others may read`() {
        rootPath.chmod(0b111_101_101).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        val refused = Err(FileError.Refused(root, info, RefusedBecause.OthersMayRead, rootPath))
        assertEquals(refused, rootPath.privateWriter())
        assertEquals(refused, rootPath.privateWriter(create = false))
    }

    @Test
    fun `privateWriter refuses a root only its group may read`() {
        rootPath.chmod(0b111_100_000).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayRead, rootPath)),
            rootPath.privateWriter(),
        )
    }

    @Test
    fun `privateWriter takes a root only its owner may read`() {
        rootPath.chmod(0b111_000_000).assertSuccess()

        assertEquals(root, rootPath.privateWriter().assertSuccess().root)
    }

    @Test
    fun `a private writer refuses its root once others may read it`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        rootPath.chmod(0b111_101_101).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayRead, root / "f")),
            private.writeText("f", "x"),
        )
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `a private child writer refuses a folder others may read`() {
        createDirectoriesRaw(rootPath / "c")
        if (!hasPermissions()) return
        val info = (rootPath / "c").info().assertSuccess()

        val refused = Err(FileError.Refused(root / "c", info, RefusedBecause.OthersMayRead, root / "c"))
        assertEquals(refused, writer.privateWriter("c"))
    }

    @Test
    fun `a private child of a writer that takes a root others may read takes such a folder too`() {
        val trusted = rootPath.newWriter(RootRule.Trusted, create = false).assertSuccess()
        createDirectoriesRaw(rootPath / "c")

        val child = trusted.privateWriter("c").assertSuccess()

        assertTrue(child.isPrivate)
        assertEquals(Ok(Unit), child.writeText("f", "x").map { })
        (rootPath / "c" / "f").assertPermissions(0b110_000_000)
    }

    @Test
    fun `a private child of a writer that takes a root others may read refuses a folder others may write`() {
        val trusted = rootPath.newWriter(RootRule.Trusted, create = false).assertSuccess()
        createDirectoriesRaw(rootPath / "c", 0b111_111_101)
        if (!hasPermissions()) return
        val info = (rootPath / "c").info().assertSuccess()

        val refused = Err(FileError.Refused(root / "c", info, RefusedBecause.OthersMayWrite, root / "c"))
        assertEquals(refused, trusted.privateWriter("c"))
    }

    @Test
    fun `a private writer refuses its root once others may write it`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        rootPath.chmod(0b111_111_111).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root, info, RefusedBecause.OthersMayWrite, root / "f")),
            private.writeText("f", "x"),
        )
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `a root moved away is FolderReplaced`() {
        SystemFileSystem.atomicMove(rootPath, dir / "moved")

        assertEquals(Err(FileError.FolderReplaced(root)), writer.writeText("f", "x"))
        assertEquals(emptyList(), SystemFileSystem.list(dir / "moved"))
    }

    @Test
    fun `a root replaced by a link to itself is FolderReplaced`() {
        SystemFileSystem.atomicMove(rootPath, dir / "moved")
        rootPath.createSymlink(dir / "moved").assertSuccess()

        assertEquals(Err(FileError.FolderReplaced(root)), writer.writeText("f", "x"))
        assertEquals(emptyList(), SystemFileSystem.list(dir / "moved"))
    }

    @Test
    fun `a child writer whose folder was replaced is FolderReplaced`() {
        val child = writer.writer("c").assertSuccess()
        SystemFileSystem.atomicMove(rootPath / "c", rootPath / "old")
        createDirectoriesRaw(rootPath / "c")

        assertEquals(Err(FileError.FolderReplaced(child.root)), child.writeText("f", "x"))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath / "c"))
    }

    @Test
    fun `an absolute name is InvalidRelativePath`() {
        val refused = writer.writeText("/etc/passwd", "x")

        assertEquals(Err(FileError.InvalidRelativePath(root, "/etc/passwd", RelativePathProblem.Absolute)), refused)
    }

    // --- links below the root

    @Test
    fun `a link on the way leading inside is followed`() {
        createDirectoriesRaw(rootPath / "real")
        (rootPath / "in").createSymlink(Path("real")).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("in/f", "x").way())
        assertEquals("x", (rootPath / "real" / "f").readRaw())
    }

    @Test
    fun `an absolute link on the way leading inside is followed`() {
        createDirectoriesRaw(rootPath / "real")
        (rootPath / "in").createSymlink(root / "real").assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("in/f", "x").way())
        assertEquals("x", (rootPath / "real" / "f").readRaw())
    }

    @Test
    fun `a link that steps above the root and comes back is Escapes`() {
        createDirectoriesRaw(rootPath / "real")
        (rootPath / "back").createSymlink(Path("../root/real")).assertSuccess()

        val escaped = Err(FileError.Escapes(root / "back" / "f", boundary = root, resolved = root / "real"))
        assertEquals(escaped, writer.writeText("back/f", "x"))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath / "real"))
    }

    @Test
    fun `a link that leaves its folder and comes back is Escapes for a writer and followed by an Inside walk`() {
        val stateDir = (dir / "state").also { createDirectoriesRaw(it) }
        (stateDir / "b").writeRaw("b")
        (stateDir / "a").createSymlink(Path("../state/b")).assertSuccess()
        val state = stateDir.writer().assertSuccess()

        val escaped = Err(FileError.Escapes(state.root / "a", boundary = state.root, resolved = state.root / "b"))
        assertEquals(escaped, state.readText("a"))
        val walked = stateDir.walkTopDown().assertSuccess().map { it.assertSuccess().relative }.toList()
        assertEquals(listOf(Path("a"), Path("b")), walked)
    }

    @Test
    fun `a link at the name leading inside is read through`() {
        createDirectoriesRaw(rootPath / "real")
        (rootPath / "real" / "f").writeRaw("inside")
        (rootPath / "x").createSymlink(Path("real/f")).assertSuccess()

        assertEquals(Ok("inside"), writer.readText("x"))
    }

    @Test
    fun `links that go round are Inaccessible with Loop`() {
        (rootPath / "a").createSymlink(Path("b")).assertSuccess()
        (rootPath / "b").createSymlink(Path("a")).assertSuccess()

        val failed = writer.writeText("a/f", "x").assertError<FileError.Inaccessible>()
        assertEquals(root / "a" / "f", failed.path)
        assertEquals(IoCause.Loop, failed.cause)
    }

    @Test
    fun `a child writer follows a link up to its parent's root`() {
        val child = writer.writer("c").assertSuccess()
        (rootPath / "c" / "up").createSymlink(Path("..")).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), child.writeText("up/f", "x").way())
        assertEquals("x", (rootPath / "f").readRaw())
    }

    @Test
    fun `a child writer refuses a link leading out of its parent's root`() {
        val child = writer.writer("c").assertSuccess()
        (rootPath / "c" / "out").createSymlink(Path("../../outside")).assertSuccess()

        val escaped = Err(FileError.Escapes(child.root / "out" / "f", boundary = root, resolved = outsideReal))
        assertEquals(escaped, child.writeText("out/f", "x"))
        assertEquals(emptyList(), SystemFileSystem.list(outside))
    }

    // --- modes

    @Test
    fun `a shared writer caps a mode kept by keepAttributes at 755`() {
        if (!hasPermissions()) return
        val wide = (dir / "wide").writeRaw("copied")
        wide.chmod(0b111_111_111).assertSuccess()
        val sticky = (dir / "sticky").writeRaw("copied")
        sticky.chmod(0b001_111_101_101).assertSuccess()
        sticky.assertPermissions(0b001_111_101_101)

        writer.copyFrom(wide, "wide", keepAttributes = true).assertSuccess()
        writer.copyFrom(sticky, "sticky", keepAttributes = true).assertSuccess()

        assertEquals(
            mapOf("wide" to Permissions(0b111_101_101), "sticky" to Permissions(0b111_101_101)),
            listOf("wide", "sticky").associateWith { (rootPath / it).permissions().assertSuccess() },
        )
    }

    @Test
    fun `a shared writer's copy with keepAttributes of a private writer's file stays 600`() {
        if (!hasPermissions()) return
        val secrets = (dir / "secrets").privateWriter().assertSuccess()
        secrets.writeText("token", "s3cret").assertSuccess()

        writer.copyFrom(dir / "secrets" / "token", "token", keepAttributes = true).assertSuccess()

        (rootPath / "token").assertPermissions(0b110_000_000)
    }

    // --- private and trusted

    @Test
    fun `a private writer makes files and folders for its owner alone`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        val source = (dir / "source").writeRaw("copied")
        source.chmod(0b110_100_100).assertSuccess()

        private.writeText("a/b/f", "x").assertSuccess()
        private.writeText("run", "x", executable = true).assertSuccess()
        private.copyFrom(source, "copy", keepAttributes = true).assertSuccess()
        private.openAppend("log").assertSuccess().close()
        private.tryLock(".lock").assertSuccess().close()
        private.createDirectories("c/d").assertSuccess()

        (rootPath / "a").assertPermissions(0b111_000_000)
        (rootPath / "a" / "b").assertPermissions(0b111_000_000)
        (rootPath / "a" / "b" / "f").assertPermissions(0b110_000_000)
        (rootPath / "run").assertPermissions(0b111_000_000)
        (rootPath / "copy").assertPermissions(0b110_000_000)
        (rootPath / "log").assertPermissions(0b110_000_000)
        (rootPath / ".lock").assertPermissions(0b110_000_000)
        (rootPath / "c").assertPermissions(0b111_000_000)
        (rootPath / "c" / "d").assertPermissions(0b111_000_000)
    }

    @Test
    fun `a private writer refuses to read a file others may write`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        val file = (rootPath / "f").writeRaw("x")
        file.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        val info = file.info().assertSuccess()

        assertEquals(
            Err(FileError.Refused(root / "f", info, RefusedBecause.OthersMayWrite, root / "f")),
            private.readText("f"),
        )
    }

    @Test
    fun `a private writer refuses to read a file others may read`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        val file = (rootPath / "f").writeRaw("x")
        file.chmod(0b110_100_000).assertSuccess()
        if (!hasPermissions()) return
        val info = file.info().assertSuccess()

        val refused = Err(FileError.Refused(root / "f", info, RefusedBecause.OthersMayRead, root / "f"))
        assertEquals(refused, private.readText("f"))
        assertEquals(refused, private.readBytes("f"))
        assertEquals(refused, private.readPrefix("f", 1))
    }

    @Test
    fun `a private writer reads a file only its owner may read`() {
        rootPath.chmod(0b111_000_000).assertSuccess()
        val private = rootPath.privateWriter().assertSuccess()
        (rootPath / "f").writeRaw("x").chmod(0b110_000_000).assertSuccess()

        assertEquals(Ok("x"), private.readText("f"))
    }

    @Test
    fun `a trusted read of a shared writer takes a file others may read`() {
        (rootPath / "f").writeRaw("x").chmod(0b110_100_100).assertSuccess()

        assertEquals(Ok("x"), writer.readText("f", trusted = true))
    }

    @Test
    fun `a shared writer reads a file others may write`() {
        val file = (rootPath / "f").writeRaw("x")
        file.chmod(0b110_110_110).assertSuccess()

        assertEquals(Ok("x"), writer.readText("f"))
    }

    @Test
    fun `a trusted read refuses a file others may write`() {
        val file = (rootPath / "f").writeRaw("x")
        file.chmod(0b110_100_110).assertSuccess()
        if (!hasPermissions()) return
        val info = file.info().assertSuccess()

        val refused = Err(FileError.Refused(root / "f", info, RefusedBecause.OthersMayWrite, root / "f"))
        assertEquals(refused, writer.readText("f", trusted = true))
        assertEquals(refused, writer.readBytes("f", trusted = true))
    }

    @Test
    fun `a trusted read refuses a folder on the way others may write`() {
        val open = (rootPath / "open").also { createDirectoriesRaw(it) }
        (open / "f").writeRaw("x").chmod(0b110_100_100).assertSuccess()
        open.chmod(0b111_111_111).assertSuccess()
        if (!hasPermissions()) return
        val info = open.info().assertSuccess()

        val refused = writer.readText("open/f", trusted = true)
        assertEquals(
            Err(FileError.Refused(root / "open", info, RefusedBecause.OthersMayWrite, root / "open/f")),
            refused,
        )
        open.chmod(0b111_000_000).assertSuccess()
    }

    @Test
    fun `a trusted read refuses a root others may write`() {
        (rootPath / "f").writeRaw("x").chmod(0b110_100_100).assertSuccess()
        rootPath.chmod(0b111_111_111).assertSuccess()
        if (!hasPermissions()) return
        val info = rootPath.info().assertSuccess()

        val refused = Err(FileError.Refused(root, info, RefusedBecause.OthersMayWrite, root / "f"))
        assertEquals(refused, writer.readText("f", trusted = true))
        assertEquals(refused, writer.readBytes("f", trusted = true))
        assertEquals(Ok("x"), writer.readText("f"))
    }

    @Test
    fun `a trusted read takes a file only its owner may write`() {
        (rootPath / "f").writeRaw("x").chmod(0b110_100_100).assertSuccess()

        assertEquals(Ok("x"), writer.readText("f", trusted = true))
    }

    // --- writes

    @Test
    fun `a write cuts other hard links`() {
        val file = (rootPath / "h").writeRaw("old")
        (outside / "other").hardLinkTo(file).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("h", "new").way())
        assertEquals("new", file.readRaw())
        assertEquals("old", (outside / "other").readRaw())
    }

    @Test
    fun `an in-place write keeps other hard links`() {
        val file = (rootPath / "h").writeRaw("old")
        (outside / "other").hardLinkTo(file).assertSuccess()

        assertEquals(Ok(WriteWay.InPlace), writer.writeText("h", "new", inPlace = true).way())
        assertEquals("new", (outside / "other").readRaw())
    }

    @Test
    fun `an in-place write with replaceLink replaces a link at the name`() {
        val theirs = (outside / "theirs").writeRaw("theirs")
        (rootPath / "x").createSymlink(theirs).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("x", "new", inPlace = true, replaceLink = true).way())
        assertEquals("theirs", theirs.readRaw())
        assertEquals("new", (rootPath / "x").readRaw())
    }

    /** A crash's backup of `p.md` beside it, as a killed in-place write leaves one, and the file it was cut short. */
    private fun leaveBackupOfP(): Leftover.Backup {
        (rootPath / "p.md").writeRaw("half")
        val copy = (rootPath / ".p.md.$A_WRITE_ID.bak").writeRaw("whole")
        return Leftover.Backup(root / "p.md", root / copy.name, A_WRITE_START)
    }

    @Test
    fun `a write of a file with a backup beside it is PendingBackup and writes nothing`() {
        val backup = leaveBackupOfP()
        val before = rootPath.tree()

        val pending = Err(FileError.PendingBackup(root / "p.md", backup))
        assertEquals(pending, writer.writeText("p.md", "new"))
        assertEquals(pending, writer.writeText("p.md", "new", inPlace = true))
        assertEquals(pending, writer.writeBytes("p.md", "new".encodeToByteArray()))
        assertEquals(pending, writer.copyFrom((outside / "s").writeRaw("new"), "p.md"))
        assertEquals(before, rootPath.tree())
    }

    @Test
    fun `a write of a missing file whose backup is beside it is PendingBackup`() {
        val copy = (rootPath / ".p.md.$A_WRITE_ID.bak").writeRaw("whole")
        val backup = Leftover.Backup(root / "p.md", root / copy.name, A_WRITE_START)

        assertEquals(Err(FileError.PendingBackup(root / "p.md", backup)), writer.writeText("p.md", "new"))
        assertEquals(mapOf(copy.name to "whole"), rootPath.tree())
    }

    @Test
    fun `a write of a file an in-place write is still writing is PendingBackup naming its backup`() {
        (rootPath / "p.md").writeRaw("half")
        val running = (rootPath / "p.md").leaveLeftover(LeftoverKind.Backup, content = "whole")

        val pending = writer.writeText("p.md", "new").assertError<FileError.PendingBackup>()
        assertEquals(root / running.name, pending.backup.copy)
        assertEquals(mapOf("p.md" to "half", running.name to "whole"), rootPath.tree())
    }

    @Test
    fun `PendingBackup names the oldest of two backups`() {
        val oldest = leaveBackupOfP()
        (rootPath / "p.md").leaveLeftover(LeftoverKind.Backup, content = "later")

        assertEquals(Err(FileError.PendingBackup(root / "p.md", oldest)), writer.writeText("p.md", "new"))
    }

    @Test
    fun `a write works once the backup was restored`() {
        writer.restore(leaveBackupOfP()).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("p.md", "new").way())
        assertEquals(mapOf("p.md" to "new"), rootPath.tree())
    }

    @Test
    fun `a write works once the backup was discarded`() {
        writer.discard(leaveBackupOfP()).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("p.md", "new").way())
        assertEquals(mapOf("p.md" to "new"), rootPath.tree())
    }

    @Test
    fun `a write keeps the temps and partial backups of its file`() {
        val left = listOf(".p.md.$A_WRITE_ID.tmp", ".p.md.$A_WRITE_ID.bak.part").associateWith { "left" }
        left.keys.forEach { (rootPath / it).writeRaw("left") }
        val running = (rootPath / "p.md").leaveLeftover(LeftoverKind.Temp, content = "theirs")

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("p.md", "new").way())
        assertEquals(left + mapOf("p.md" to "new", running.name to "theirs"), rootPath.tree())
    }

    @Test
    fun `a write beside a backup of another file writes`() {
        (rootPath / ".q.md.$A_WRITE_ID.bak").writeRaw("theirs")

        assertEquals(Ok(WriteWay.Renamed), writer.writeText("p.md", "new").way())
        assertEquals(mapOf(".q.md.$A_WRITE_ID.bak" to "theirs", "p.md" to "new"), rootPath.tree())
    }

    @Test
    fun `an append to a file with a backup beside it appends and keeps the backup`() {
        val _ = leaveBackupOfP()

        assertEquals(Ok(Unit), writer.openAppend("p.md").flatMap { file -> file.use { it.appendLine("!") } })
        assertEquals(mapOf("p.md" to "half!\n", ".p.md.$A_WRITE_ID.bak" to "whole"), rootPath.tree())
    }

    @Test
    fun `a folder listed twice while open lists every entry both times`() {
        writer.writeText("a", "a").assertSuccess()
        writer.writeText("b", "b").assertSuccess()

        openLevel(root).use { level ->
            assertEquals(listOf("a", "b"), level.list().sorted())
            assertEquals(listOf("a", "b"), level.list().sorted())
        }
    }

    // --- folders, links, moves and removals

    @Test
    fun `createDirectoryAtomically hands a stage writer bounded by the stage`() {
        var stageRoot: Path? = null
        val built = writer.createDirectoryAtomically("s") { stage ->
            stageRoot = stage.root
            (stage.root / "up").createSymlink(Path("..")).assertSuccess()
            (stage.root / "out").createSymlink(outside).assertSuccess()
            Triple(stage.writeText("f", "x"), stage.writeText("up/g", "y"), stage.writeText("out/h", "z"))
        }

        val stage = checkNotNull(stageRoot)
        val placed = root / "s"
        val up = Err(FileError.Escapes(placed / "up" / "g", boundary = placed, resolved = root))
        val out = Err(FileError.Escapes(placed / "out" / "h", boundary = placed, resolved = outsideReal))
        assertEquals(Ok(Triple(Ok(WriteWay.Renamed), up, out)), built.map { (f, g, h) -> Triple(f.way(), g, h) })
        assertEquals(root, stage.parent)
        assertEquals("x", (rootPath / "s" / "f").readRaw())
        assertFalse(SystemFileSystem.exists(rootPath / "g"))
        assertEquals(emptyList(), SystemFileSystem.list(outside))
    }

    @Test
    fun `a stage writer refuses a link the build points above the stage as Escapes`() {
        var stageRoot: Path? = null
        val switched = writer.createDirectoryAtomically("v1") { stage ->
            stageRoot = stage.root
            stage.switchSymlink("up", Path("..")) to stage.switchSymlink("up2", Path("../.."))
        }

        val placed = root / "v1"
        val up = Err(FileError.Escapes(placed / "up", boundary = placed, resolved = root))
        val up2 = Err(FileError.Escapes(placed / "up2", boundary = placed, resolved = checkNotNull(root.parent)))
        assertEquals(Ok(up to up2), switched)
        assertTrue(checkNotNull(stageRoot).name.endsWith(".dir"))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath / "v1"))
    }

    @Test
    fun `a stage writer never writes beside the folder it builds through a link to its parent`() {
        var stageRoot: Path? = null
        val written = writer.createDirectoryAtomically("v1") { stage ->
            stageRoot = stage.root
            (stage.root / "up").createSymlink(Path("..")).assertSuccess()
            stage.writeText("up/other/pwned", "x")
        }

        val placed = root / "v1"
        val escaped = Err(FileError.Escapes(placed / "up" / "other" / "pwned", boundary = placed, resolved = root))
        assertEquals(Ok(escaped), written)
        assertTrue(checkNotNull(stageRoot).name.endsWith(".dir"))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `a stage writer names a bad name by the folder it builds`() {
        val refused = writer.createDirectoryAtomically("pkg/1.0") { stage -> stage.writeText("../x", "y") }

        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)
        assertEquals(Ok(Err(FileError.InvalidRelativePath(root / "pkg" / "1.0", "../x", problem))), refused)
    }

    @Test
    fun `a stage writer names a failed write by the folder it builds with the stage path at the spot refused`() {
        var lib: Path? = null
        val written = writer.createDirectoryAtomically("pkg/1.0") { stage ->
            stage.createDirectories("lib").assertSuccess()
            lib = stage.root / "lib"
            val folder = checkNotNull(lib)
            var failed: Result<Written, FileError>? = null
            folder.withMode(0b101_101_101, stillWorks = { stage.writeText("lib/probe", "x") is Result.Success }) {
                failed = stage.writeText("lib/up", "x")
            }
            failed
        }

        // Skipped where the mode does not stop a write, as for root, which leaves nothing to observe.
        val failed = written.assertSuccess() ?: return
        val failure = assertIs<FileError.WriteFailed>(failed.errorOrNull())
        assertEquals(root / "pkg" / "1.0" / "lib" / "up", failure.path)
        assertEquals(IoCause.Denied, failure.cause)
        assertEquals(checkNotNull(lib), assertIs<FailedAt.Temp>(failure.at).path.parent)
    }

    @Test
    fun `a stage writer root is the stage while it builds`() {
        val stageRoot = writer.createDirectoryAtomically("pkg") { stage -> stage.root }.assertSuccess()

        assertEquals(root, stageRoot.parent)
        assertTrue(stageRoot.name.startsWith(".pkg.") && stageRoot.name.endsWith(".dir"))
    }

    @Test
    fun `a stage writer follows a link it makes to a folder inside the stage`() {
        val built = writer.createDirectoryAtomically("v1") { stage ->
            stage.createDirectories("real").assertSuccess()
            stage.switchSymlink("current", Path("real")).assertSuccess()
            stage.writeText("current/f", "x")
        }

        assertEquals(Ok(Ok(WriteWay.Renamed)), built.map { it.way() })
        assertEquals("x", (rootPath / "v1" / "real" / "f").readRaw())
        assertEquals(Ok(Path("real")), (rootPath / "v1" / "current").readSymlink())
    }

    @Test
    fun `createDirectoryAtomically places nothing when the build answers an Err`() {
        val gaveUp = Err(FileError.NotFound(dir / "x"))

        assertEquals(Ok(gaveUp), writer.createDirectoryAtomically("s") { gaveUp })
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `createDirectoryAtomically of a name already taken is AlreadyExists and never calls the build`() {
        createDirectoriesRaw(rootPath / "folder", mode = 0b111_101_101)
        (rootPath / "file").writeRaw("f")
        (rootPath / "link").createSymlink(Path("missing")).assertSuccess()
        var builds = 0

        val answers = listOf("folder", "file", "link").map { name ->
            writer.createDirectoryAtomically(name) { builds++ }
        }

        assertEquals(0, builds)
        assertEquals(
            listOf<Result<Int, FileError>>(
                Err(FileError.AlreadyExists(root / "folder", FileType.Directory)),
                Err(FileError.AlreadyExists(root / "file", FileType.Regular)),
                Err(FileError.AlreadyExists(root / "link", FileType.Symlink)),
            ),
            answers,
        )
    }

    @Test
    fun `a stage writer is FolderReplaced once its folder is placed`() {
        val stage = writer.createDirectoryAtomically("s") { it }.assertSuccess()

        assertEquals(Err(FileError.FolderReplaced(stage.root)), stage.writeText("late", "x"))
    }

    @Test
    fun `a build whose folder is replaced is FolderReplaced and leaves its stage where the folder went`() {
        val moved = dir / "moved"

        val built = writer.createDirectoryAtomically("pkg") { stage ->
            stage.writeText("a.txt", "a").assertSuccess()
            SystemFileSystem.atomicMove(rootPath, moved)
            createDirectoriesRaw(rootPath)
        }

        assertEquals(Err(FileError.FolderReplaced(root)), built)
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
        val staged = moved.leftoversIn().assertSuccess().found.single()
        assertEquals(Leftover.StagedFolder::class, staged::class)
        assertEquals(Ok(Unit), moved.writer().assertSuccess().discard(staged))
        assertEquals(emptyMap(), moved.tree())
    }

    @Test
    fun `switchSymlink refuses a folder at the name`() {
        createDirectoriesRaw(rootPath / "d")

        val refused = Err(FileError.NotRegularFile(root / "d", FileType.Directory))
        assertEquals(refused, writer.switchSymlink("d", Path("x")))
    }

    @Test
    fun `switchSymlink refuses a file at the name`() {
        (rootPath / "f").writeRaw("x")

        assertEquals(Err(FileError.AlreadyExists(root / "f", FileType.Regular)), writer.switchSymlink("f", Path("x")))
    }

    @Test
    fun `switchSymlink judges the target by its names so a target that is a link leading out is switched to`() {
        (outside / "x.txt").writeRaw("outside")
        (rootPath / "9.9").createSymlink(outside).assertSuccess()

        assertEquals(Ok(Unit), writer.switchSymlink("current", Path("9.9")))

        assertEquals(Path("9.9"), readLink(rootPath / "current"))
        writer.readText("current/x.txt").assertError<FileError.Escapes>()
    }

    @Test
    fun `switchSymlink takes a target inside the root`() {
        assertEquals(Ok(Unit), writer.switchSymlink("current", Path("releases", "43")))
        assertEquals(Ok(Unit), writer.switchSymlink("sub/up", Path("..", "releases")))
        assertEquals(Ok(Unit), writer.switchSymlink("absolute", Path(root.toString(), "releases")))

        assertEquals(Path("releases", "43"), readLink(rootPath / "current"))
        assertEquals(Path("..", "releases"), readLink(rootPath / "sub" / "up"))
    }

    @Test
    fun `switchSymlink takes a missing target inside the root`() {
        assertEquals(Ok(Unit), writer.switchSymlink("current", Path("releases", "44")))
        assertFalse(SystemFileSystem.exists(rootPath / "releases"))
    }

    @Test
    fun `switchSymlink refuses an absolute target outside the root as Escapes`() {
        val target = Path(outsideReal.toString(), "etc")

        assertEquals(Err(FileError.Escapes(root / "current", root, target)), writer.switchSymlink("current", target))
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `switchSymlink refuses a target that steps above the root as Escapes`() {
        val target = Path("..", "..", "..", "home")

        val resolved = Path(checkNotNull(root.parent?.parent?.parent), "home")
        val escaped = Err(FileError.Escapes(root / "current", root, resolved))
        assertEquals(escaped, writer.switchSymlink("current", target))
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `switchSymlink refuses a target that leaves the root and comes back as Escapes`() {
        val target = Path("..", root.name, "releases")

        val escaped = Err(FileError.Escapes(root / "current", root, root / "releases"))
        assertEquals(escaped, writer.switchSymlink("current", target))
    }

    @Test
    fun `switchSymlink of a refused target makes no folder on the way`() {
        val target = Path("..", "..", "..", "x")
        val folder = root / "a" / "b"

        val escaped = Err(FileError.Escapes(folder / "current", root, Path(checkNotNull(root.parent), "x")))
        assertEquals(escaped, writer.switchSymlink("a/b/current", target))
        val itself = Err(FileError.InvalidName(root / "s" / "current", NameProblem.LinksToItself))
        assertEquals(itself, writer.switchSymlink("s/current", Path("current")))
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `switchSymlink judges a target from the real folder a link on the way leads to`() {
        createDirectoriesRaw(rootPath / "deep" / "er")
        (rootPath / "in").createSymlink(Path("deep", "er")).assertSuccess()

        assertEquals(Ok(Unit), writer.switchSymlink("in/new/current", Path("..", "..", "..", "releases")))
        assertEquals(Path("..", "..", "..", "releases"), readLink(rootPath / "deep" / "er" / "new" / "current"))
    }

    @Test
    fun `switchSymlink's target check and a walk following a link with that target agree`() {
        (rootPath / "data.txt").writeRaw("in")
        val outsideFile = (outside / "data.txt").writeRaw("out")
        createDirectoriesRaw(rootPath / "sub")
        val targets = mapOf(
            "inside" to Path("..", "data.txt"),
            "outAndBack" to Path("..", "..", root.name, "data.txt"),
            "absoluteInside" to Path(root.toString(), "data.txt"),
            "absoluteOutside" to Path(outsideReal.toString(), outsideFile.name),
            "aboveBoundary" to Path("..", "..", "..", "data.txt"),
        )
        val expected = mapOf(
            "inside" to true,
            "outAndBack" to false,
            "absoluteInside" to true,
            "absoluteOutside" to false,
            "aboveBoundary" to false,
        )

        val checked = targets.mapValues { (_, target) -> linkedNamesBelow(root, root / "sub", target) != null }
        val followed = targets.mapValues { (label, target) ->
            (rootPath / "sub" / label).createSymlink(target).assertSuccess()
            writer.readText("sub/$label").let { it !is Result.Error || it.error !is FileError.Escapes }
        }
        assertEquals(expected, checked)
        assertEquals(expected, followed)
    }

    @Test
    fun `switchSymlink refuses a target naming the link itself as LinksToItself`() {
        val itself = Err(FileError.InvalidName(root / "sub" / "current", NameProblem.LinksToItself))

        assertEquals(itself, writer.switchSymlink("sub/current", Path("current")))
        assertEquals(itself, writer.switchSymlink("sub/current", Path("..", "sub", "current")))
        assertEquals(itself, writer.switchSymlink("sub/current", Path(root.toString(), "sub", "current")))
        assertFalse(isSymlink(rootPath / "sub" / "current"))
    }

    @Test
    fun `readSymlink answers the target a link holds`() {
        writer.switchSymlink("current", Path("releases", "43")).assertSuccess()
        (rootPath / "out").createSymlink(outside).assertSuccess()

        assertEquals(Ok(Path("releases", "43")), writer.readSymlink("current"))
        assertEquals(Ok(outside), writer.readSymlink("out"))
    }

    @Test
    fun `readSymlink of a file is NotASymlink`() {
        (rootPath / "f").writeRaw("x")

        assertEquals(Err(FileError.NotASymlink(root / "f", FileType.Regular)), writer.readSymlink("f"))
    }

    @Test
    fun `readSymlink of nothing is NotFound`() {
        assertEquals(Err(FileError.NotFound(root / "gone")), writer.readSymlink("gone"))
    }

    @Test
    fun `readSymlink of a bad name is InvalidRelativePath`() {
        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)

        assertEquals(Err(FileError.InvalidRelativePath(root, "../x", problem)), writer.readSymlink("../x"))
    }

    @Test
    fun `info without following links describes the link itself`() {
        (rootPath / "f").writeRaw("x")
        (rootPath / "l").createSymlink(Path("f")).assertSuccess()

        assertEquals(Ok(FileType.Symlink), writer.info("l", followLinks = false).map { it.type })
        assertEquals(Ok(FileType.Regular), writer.info("l").map { it.type })
        assertEquals(Ok(FileType.Regular), writer.info("f", followLinks = false).map { it.type })
    }

    @Test
    fun `info without following links describes a link leading out`() {
        (rootPath / "out").createSymlink(outside).assertSuccess()

        assertEquals(Ok(FileType.Symlink), writer.info("out", followLinks = false).map { it.type })
        writer.info("out").assertError<FileError.Escapes>()
    }

    @Test
    fun `move of a missing name is NotFound`() {
        assertEquals(Err(FileError.NotFound(root / "nothing")), writer.move("nothing", "else"))
    }

    @Test
    fun `move refuses a file onto a folder`() {
        (rootPath / "f").writeRaw("x")
        createDirectoriesRaw(rootPath / "d")

        assertEquals(Err(FileError.NotRegularFile(root / "d", FileType.Directory)), writer.move("f", "d"))
    }

    @Test
    fun `delete of a folder with entries is WriteFailed`() {
        createDirectoriesRaw(rootPath / "d")
        (rootPath / "d" / "f").writeRaw("x")

        writer.delete("d").assertError<FileError.WriteFailed>()
        assertEquals("x", (rootPath / "d" / "f").readRaw())
    }

    @Test
    fun `deleteRecursively removes links inside as links`() {
        (outside / "kept").writeRaw("kept")
        createDirectoriesRaw(rootPath / "t")
        (rootPath / "t" / "out").createSymlink(outside).assertSuccess()

        assertEquals(Ok(Unit), writer.deleteRecursively("t"))
        assertEquals(mapOf("kept" to "kept"), outside.tree())
    }

    @Test
    fun `deleteRecursively of a missing name is Ok`() {
        assertEquals(Ok(Unit), writer.deleteRecursively("nothing"))
    }

    @Test
    fun `openAppend with Reopen WhenMoved goes on in the new file at the name`() {
        writer.openAppend("app.log", Reopen.WhenMoved()).assertSuccess().use { log ->
            log.appendLine("first").assertSuccess()
            writer.move("app.log", "app.log.1").assertSuccess()
            log.appendLine("second").assertSuccess()
        }

        assertEquals("first\n", (rootPath / "app.log.1").readRaw())
        assertEquals("second\n", (rootPath / "app.log").readRaw())
    }

    @Test
    fun `a reopen refuses a link put at the name and keeps the line for the next append`() {
        val theirs = (outside / "theirs.log").writeRaw("theirs\n")
        writer.openAppend("app.log", Reopen.WhenMoved()).assertSuccess().use { log ->
            log.appendLine("first").assertSuccess()
            writer.move("app.log", "app.log.1").assertSuccess()
            (rootPath / "app.log").createSymlink(theirs).assertSuccess()

            assertEquals(Err(FileError.NotRegularFile(root / "app.log", FileType.Symlink)), log.appendLine("second"))
            (rootPath / "app.log").delete().assertSuccess()
            log.appendLine("second").assertSuccess()
        }

        assertEquals("theirs\n", theirs.readRaw())
        assertEquals("first\n", (rootPath / "app.log.1").readRaw())
        assertEquals("second\n", (rootPath / "app.log").readRaw())
    }

    @Test
    fun `copyFrom keeps the source's modification time with keepAttributes`() {
        val source = (dir / "source").writeRaw("copied")
        val longAgo = Instant.fromEpochSeconds(1_000_000_000)
        source.setModifiedAt(longAgo).assertSuccess()

        writer.copyFrom(source, "copy", keepAttributes = true).assertSuccess()

        assertEquals(longAgo, (rootPath / "copy").modifiedAt().assertSuccess())
    }

    @Test
    fun `copyFrom of a missing source is NotFound naming the source`() {
        assertEquals(Err(FileError.NotFound(dir / "nothing")), writer.copyFrom(dir / "nothing", "copy"))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    // --- listing

    @Test
    fun `list of the root answers bare names in name order`() {
        (rootPath / "b.txt").writeRaw("b")
        (rootPath / "a.txt").writeRaw("a")
        createDirectoriesRaw(rootPath / "pkgs")

        assertEquals(Ok(listOf("a.txt", "b.txt", "pkgs")), writer.list())
    }

    @Test
    fun `list of a folder answers names relative to the root that the other calls take`() {
        createDirectoriesRaw(rootPath / "pkgs" / "b")
        (rootPath / "pkgs" / "a").writeRaw("a")

        val names = writer.list("pkgs").assertSuccess()

        assertEquals(listOf("pkgs/a", "pkgs/b"), names)
        assertEquals(Ok("a"), writer.readText(names[0]))
        assertEquals(FileType.Directory, writer.info(names[1]).assertSuccess().type)
        assertEquals(Ok(Unit), writer.delete(names[1]))
    }

    @Test
    fun `list answers each entry as itself and leftovers too`() {
        (rootPath / "real").writeRaw("x")
        (rootPath / "link").createSymlink(Path("real")).assertSuccess()
        (rootPath / "out").createSymlink(outside).assertSuccess()
        (rootPath / ".real.$A_WRITE_ID.tmp").writeRaw("half")

        assertEquals(Ok(listOf(".real.$A_WRITE_ID.tmp", "link", "out", "real")), writer.list())
        assertEquals(Ok(Unit), writer.delete(".real.$A_WRITE_ID.tmp"))
    }

    @Test
    fun `list of a link to a folder inside lists that folder under the name asked`() {
        createDirectoriesRaw(rootPath / "releases" / "43")
        (rootPath / "releases" / "43" / "app").writeRaw("app")
        (rootPath / "current").createSymlink(Path("releases/43")).assertSuccess()

        assertEquals(Ok(listOf("current/app")), writer.list("current"))
        assertEquals(Ok("app"), writer.readText("current/app"))
    }

    @Test
    fun `list through a link leading out is Escapes`() {
        (outside / "theirs").writeRaw("x")
        (rootPath / "out").createSymlink(outside).assertSuccess()

        assertEquals(Err(FileError.Escapes(root / "out", boundary = root, resolved = outsideReal)), writer.list("out"))
        assertEquals(
            Err(FileError.Escapes(root / "out" / "sub", boundary = root, resolved = outsideReal)),
            writer.list("out/sub"),
        )
    }

    @Test
    fun `list of a file is NotADirectory`() {
        (rootPath / "f").writeRaw("x")

        assertEquals(Err(FileError.NotADirectory(root / "f", FileType.Regular)), writer.list("f"))
    }

    @Test
    fun `list of a missing folder is NotFound`() {
        assertEquals(Err(FileError.NotFound(root / "nothing")), writer.list("nothing"))
        assertEquals(Err(FileError.NotFound(root / "nothing" / "deeper")), writer.list("nothing/deeper"))
    }

    @Test
    fun `list of a bad name is InvalidRelativePath`() {
        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)

        assertEquals(Err(FileError.InvalidRelativePath(root, "../x", problem)), writer.list("../x"))
    }

    // --- leftovers

    @Test
    fun `leftovers with depth 1 finds a backup in a folder at the root and depth 0 does not`() {
        createDirectoriesRaw(rootPath / "sub")
        val copy = (rootPath / "sub" / ".p.md.$A_WRITE_ID.bak").writeRaw("old")

        val scan = writer.leftovers(depth = 1).assertSuccess()

        val backup = Leftover.Backup(root / "sub" / "p.md", root / "sub" / copy.name, A_WRITE_START)
        assertEquals(listOf(backup), scan.found)
        assertEquals(emptyList(), writer.leftovers().assertSuccess().found)
    }

    @Test
    fun `leftovers of a negative depth is an IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { writer.leftovers(depth = -1) }
    }

    @Test
    fun `discard removes a leftover through the writer`() {
        (rootPath / ".p.md.$A_WRITE_ID.tmp").writeRaw("new")
        val leftover = writer.leftovers().assertSuccess().found.single()

        assertEquals(Ok(Unit), writer.discard(leftover))
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }

    @Test
    fun `restore puts a backup back through the writer`() {
        (rootPath / "p.md").writeRaw("half")
        (rootPath / ".p.md.$A_WRITE_ID.bak").writeRaw("whole")
        val backup = writer.leftovers().assertSuccess().found.single() as Leftover.Backup

        assertEquals(Ok(Unit), writer.restore(backup))
        assertEquals(mapOf("p.md" to "whole"), rootPath.tree())
    }

    @Test
    fun `restore refuses a link at the file's name and keeps the backup`() {
        val theirs = (rootPath / "theirs.md").writeRaw("theirs")
        (rootPath / "p.md").createSymlink(theirs).assertSuccess()
        val copy = (rootPath / ".p.md.$A_WRITE_ID.bak").writeRaw("whole")
        val backup = Leftover.Backup(root / "p.md", root / copy.name, A_WRITE_START)

        assertEquals(Err(FileError.NotRegularFile(root / "p.md", FileType.Symlink)), writer.restore(backup))
        assertEquals("theirs", theirs.readRaw())
        assertEquals("whole", copy.readRaw())
    }

    @Test
    fun `a child writer's discard of a leftover outside its root names its boundary`() {
        val child = writer.writer("sub").assertSuccess()
        val copy = (rootPath / ".p.md.$A_WRITE_ID.tmp").writeRaw("new")
        val leftover = writer.leftovers().assertSuccess().found.single()

        val escaped = Err(FileError.Escapes(root / copy.name, boundary = root, resolved = root))
        assertEquals(escaped, child.discard(leftover))
        assertEquals("new", copy.readRaw())
    }

    @Test
    fun `restore puts back a backup a scan named through a linked folder`() {
        val repo = (dir / "repo-notes").also { createDirectoriesRaw(it) }
        createDirectoriesRaw(dir / "app")
        val notes = (dir / "app" / "notes").also { it.createSymlink(Path("..", "repo-notes")).assertSuccess() }
        (repo / "x.md").writeRaw("half")
        (repo / ".x.md.$A_WRITE_ID.bak").writeRaw("whole")
        val backup = notes.leftoversIn().assertSuccess().found.single() as Leftover.Backup
        assertEquals(notes / ".x.md.$A_WRITE_ID.bak", backup.copy)

        assertEquals(Ok(Unit), notes.writer().assertSuccess().restore(backup))

        assertEquals(mapOf("x.md" to "whole"), repo.tree())
    }

    @Test
    fun `discard removes a temp a scan named through a linked folder`() {
        val repo = (dir / "repo-notes").also { createDirectoriesRaw(it) }
        createDirectoriesRaw(dir / "app")
        val notes = (dir / "app" / "notes").also { it.createSymlink(Path("..", "repo-notes")).assertSuccess() }
        (repo / ".x.md.$A_WRITE_ID.tmp").writeRaw("half")
        val temp = notes.leftoversIn().assertSuccess().found.single()

        assertEquals(Ok(Unit), notes.writer().assertSuccess().discard(temp))

        assertEquals(emptyList(), SystemFileSystem.list(repo))
    }

    @Test
    fun `restore refuses a backup a link inside the root leads out to`() {
        (rootPath / "away").createSymlink(outside).assertSuccess()
        (outside / "p.md").writeRaw("half")
        val copy = (outside / ".p.md.$A_WRITE_ID.bak").writeRaw("whole")
        val backup = Leftover.Backup(rootPath / "away" / "p.md", rootPath / "away" / copy.name, A_WRITE_START)

        val escaped = Err(FileError.Escapes(backup.copy, boundary = root, resolved = outsideReal))
        assertEquals(escaped, writer.restore(backup))
        assertEquals("half", (outside / "p.md").readRaw())
        assertEquals("whole", copy.readRaw())
    }

    @Test
    fun `discard refuses a leftover outside the root`() {
        val copy = (outside / ".p.md.$A_WRITE_ID.tmp").writeRaw("new")
        val leftover = outside.leftoversIn().assertSuccess().found.single()

        assertEquals(Err(FileError.Escapes(copy, boundary = root, resolved = outsideReal)), writer.discard(leftover))
        assertEquals("new", copy.readRaw())
    }
}
