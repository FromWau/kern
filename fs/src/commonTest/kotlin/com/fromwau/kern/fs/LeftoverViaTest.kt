package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class LeftoverViaTest {
    private val dir = newTempDir()
    private val real = dir.realPath().assertSuccess()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** `dotfiles/b/l.md` cut short with its crash backup beside it, `dotfiles/b` a folder only you may change. */
    private fun leaveBackupInDotfiles(): Path {
        val folder = (dir / "dotfiles" / "b").also { createDirectoriesRaw(it) }
        folder.chmod(0b111_000_000).assertSuccess()
        (folder / "l.md").writeRaw("half")
        (folder / ".l.md.$A_WRITE_ID.bak").writeRaw("whole")
        return folder / "l.md"
    }

    /** The folder `root`, beside `dotfiles` and holding the links of a test. */
    private fun root(): Path = (dir / "root").also { createDirectoriesRaw(it) }

    @Test
    fun `a link at the name is the via of a Path write and of leftovers and a restore through links uses it`() {
        val file = leaveBackupInDotfiles()
        val link = links.make(root() / "l.md", Path("../dotfiles/b/l.md"))

        val pending = link.writeText("new").assertError<FileError.PendingBackup>()

        assertEquals(link, pending.backup.via)
        assertEquals(listOf(link), link.leftovers().assertSuccess().map { it.via })
        root().writer().assertSuccess().restore(pending.backup, throughLinks = true).assertSuccess()
        assertEquals("whole", file.readRaw())
    }

    @Test
    fun `a link at the folder is the via of a Path write and of leftovers and a restore through links uses it`() {
        val file = leaveBackupInDotfiles()
        val folder = links.make(root() / "config", Path("../dotfiles/b"))

        val pending = (folder / "l.md").writeText("new").assertError<FileError.PendingBackup>()

        assertEquals(folder, pending.backup.via)
        assertEquals(listOf(folder), (folder / "l.md").leftovers().assertSuccess().map { it.via })
        root().writer().assertSuccess().restore(pending.backup, throughLinks = true).assertSuccess()
        assertEquals("whole", file.readRaw())
    }

    @Test
    fun `a link at the folder is the via of a Writer write and a restore through links goes through it`() {
        val file = leaveBackupInDotfiles()
        createDirectoriesRaw(root() / "a")
        links.make(root() / "a" / "b", Path("../../dotfiles/b"))
        val writer = dir.writer().assertSuccess().writer("root").assertSuccess()

        val pending = writer.writeText("a/b/l.md", "new").assertError<FileError.PendingBackup>()

        assertEquals(real / "root" / "a" / "b", pending.backup.via)
        writer.restore(pending.backup, throughLinks = true).assertSuccess()
        assertEquals("whole", file.readRaw())
    }

    @Test
    fun `a link two levels up is no via and a writer of the passed over link restores the backup`() {
        val file = leaveBackupInDotfiles()
        val link = links.make(root() / "x", Path("../dotfiles"))
        val inLink = Path("x") / "b" / "l.md"
        val writer = dir.writer().assertSuccess().writer("root").assertSuccess()

        val byPath = (root() / inLink).writeText("new").assertError<FileError.PendingBackup>()
        val byWriter = writer.writeText(inLink.toString(), "new").assertError<FileError.PendingBackup>()

        assertEquals(null, byPath.backup.via)
        assertEquals(null, byWriter.backup.via)
        assertEquals(listOf(real / "root" / "x"), writer.leftovers(depth = Int.MAX_VALUE).assertSuccess().passedOver)
        val linked = link.writer(create = false).assertSuccess()
        val backup = linked.leftovers(depth = 1).assertSuccess().oldestBackups().values.single()
        linked.restore(backup).assertSuccess()
        assertEquals("whole", file.readRaw())
    }

    @Test
    fun `leftovers of a link to a folder name no via since its copies lie beside the folder`() {
        createDirectoriesRaw(dir / "dotfiles")
        (dir / ".dotfiles.$A_WRITE_ID.tmp").writeRaw("x")
        val link = links.make(root() / "dotfiles", Path("../dotfiles"))

        assertEquals(listOf(null), link.leftovers().assertSuccess().map { it.via })
    }
}
