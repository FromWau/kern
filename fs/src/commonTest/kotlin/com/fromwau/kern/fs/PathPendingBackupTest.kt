package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class PathPendingBackupTest {
    private val dir = newTempDir()
    private val real = dir.realPath().assertSuccess()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** A crash's backup of [file] beside it, as a killed in-place write leaves one, and the file it was cut short. */
    private fun leaveBackupOf(file: Path): Leftover.Backup {
        file.writeRaw("half")
        val copy = (file.parent!! / ".${file.name}.$A_WRITE_ID.bak").writeRaw("whole")
        return Leftover.Backup(real / file.name, real / copy.name, A_WRITE_START)
    }

    @Test
    fun `a Path write of a file with a backup beside it is PendingBackup and writes nothing`() {
        val file = dir / "p.md"
        val backup = leaveBackupOf(file)
        val source = (dir / "s").writeRaw("new")

        val pending = Err(FileError.PendingBackup(file, backup))
        assertEquals(pending, file.writeText("new"))
        assertEquals(pending, file.writeText("new", WriteMode.InPlace))
        assertEquals(pending, file.writeBytes("new".encodeToByteArray()))
        assertEquals(pending, source.copyTo(file))
        assertEquals(mapOf("p.md" to "half", backup.copy.name to "whole", "s" to "new"), dir.tree())
    }

    @Test
    fun `a Path write through a link is PendingBackup for a backup beside the link's target`() {
        createDirectoriesRaw(dir / "dotfiles")
        val target = dir / "dotfiles" / "l.md"
        target.writeRaw("half")
        val copy = (dir / "dotfiles" / ".l.md.$A_WRITE_ID.bak").writeRaw("whole")
        val link = links.make(dir / "l.md", Path("dotfiles/l.md"))
        val backup = Leftover.Backup(real / "dotfiles" / "l.md", real / "dotfiles" / copy.name, A_WRITE_START, link)

        assertEquals(Err(FileError.PendingBackup(link, backup)), link.writeText("new"))
        assertEquals("half", target.readRaw())
    }

    /** `dotfiles/l.md` cut short with its crash backup beside it, `dotfiles` a folder only you may change. */
    private fun leaveBackupInDotfiles() {
        val dotfiles = (dir / "dotfiles").also { createDirectoriesRaw(it) }
        dotfiles.chmod(0b111_000_000).assertSuccess()
        (dotfiles / "l.md").writeRaw("half")
        (dotfiles / ".l.md.$A_WRITE_ID.bak").writeRaw("whole")
    }

    @Test
    fun `a Path write through a folder link is PendingBackup with that link as the backup's via`() {
        leaveBackupInDotfiles()
        val folder = links.make(dir / "config", Path("dotfiles"))

        val pending = (folder / "l.md").writeText("new").assertError<FileError.PendingBackup>()

        assertEquals(folder, pending.backup.via)
        assertEquals(listOf(folder), (folder / "l.md").leftovers().assertSuccess().map { it.via })
    }

    @Test
    fun `a Writer write through a folder link is PendingBackup with that link as the backup's via`() {
        createDirectoriesRaw(dir / "elsewhere" / "b")
        createDirectoriesRaw(dir / "a")
        (dir / "elsewhere" / "b" / "x.md").writeRaw("half")
        val copy = (dir / "elsewhere" / "b" / ".x.md.$A_WRITE_ID.bak").writeRaw("whole")
        links.make(dir / "a" / "b", Path("../elsewhere/b"))

        val pending = dir.writer().assertSuccess().writeText("a/b/x.md", "new").assertError<FileError.PendingBackup>()

        assertEquals(real / "a" / "b", pending.backup.via)
        assertEquals(real / "elsewhere" / "b" / copy.name, pending.backup.copy)
    }

    @Test
    fun `a Writer write with no link on the way is PendingBackup with no via`() {
        val _ = leaveBackupOf(dir / "p.md")

        val pending = dir.writer().assertSuccess().writeText("p.md", "new").assertError<FileError.PendingBackup>()

        assertEquals(null, pending.backup.via)
    }

    @Test
    fun `a writer discards through links the backup a Path write met through a folder link leading out of its root`() {
        leaveBackupInDotfiles()
        createDirectoriesRaw(dir / "root")
        val folder = links.make(dir / "root" / "config", Path("../dotfiles"))
        val pending = (folder / "l.md").writeText("new").assertError<FileError.PendingBackup>()

        (dir / "root").writer().assertSuccess().discard(pending.backup, throughLinks = true).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), (folder / "l.md").writeText("new").way())
    }

    @Test
    fun `an append to a file with a backup beside it goes through`() {
        val file = dir / "p.md"
        val _ = leaveBackupOf(file)

        file.openAppend().assertSuccess().use { it.appendLine(" more") }.assertSuccess()

        assertEquals("half more\n", file.readRaw())
    }

    @Test
    fun `a Path write goes through once the backup was restored`() {
        val file = dir / "p.md"
        dir.writer().assertSuccess().restore(leaveBackupOf(file)).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())
        assertEquals(mapOf("p.md" to "new"), dir.tree())
    }

    @Test
    fun `a Path write goes through once the backup was discarded`() {
        val file = dir / "p.md"
        dir.writer().assertSuccess().discard(leaveBackupOf(file)).assertSuccess()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())
        assertEquals(mapOf("p.md" to "new"), dir.tree())
    }
}
