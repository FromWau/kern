package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.io.files.Path

/**
 * A config folder holding `app.toml` and `rel.toml`, links to the file a dotfiles folder keeps, the second relative, as
 * a trusted read finds them.
 */
class TrustedReadTest {
    private val dir = newTempDir()
    private val config = (dir / "config").also { createDirectoriesRaw(it) }
    private val dotfiles = (dir / "dotfiles").also { createDirectoriesRaw(it) }
    private val real = (dotfiles / "app.toml").writeRaw("a = 1")
    private val link = config / "app.toml"
    private val links = TestLinks()

    init {
        config.chmod(0b111_000_000).assertSuccess()
        dotfiles.chmod(0b111_000_000).assertSuccess()
        real.chmod(0b110_000_000).assertSuccess()
        link.createSymlink(real).assertSuccess()
        (config / "rel.toml").createSymlink(Path("../dotfiles/app.toml")).assertSuccess()
    }

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    private fun assertAnUntrustedReadLoadsIt() {
        assertEquals(Ok("a = 1"), link.readText())
        assertContentEquals("a = 1".encodeToByteArray(), link.readBytes().assertSuccess())
    }

    @Test
    fun `a trusted read loads a link to a file only you may change in a folder only you may change`() {
        assertEquals(Ok("a = 1"), link.readText(trusted = true))
        assertContentEquals("a = 1".encodeToByteArray(), link.readBytes(trusted = true).assertSuccess())
    }

    @Test
    fun `a trusted read refuses a file others may write through a link naming the file and the link asked`() {
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        val target = real.realPath().assertSuccess()
        val refused = Err(FileError.Refused(target, real.info().assertSuccess(), RefusedBecause.OthersMayWrite, link))

        assertEquals(refused, link.readText(trusted = true))
        assertEquals(refused, link.readBytes(trusted = true))
        assertEquals("Refused($target for $link: OthersMayWrite, rw-rw-rw-)", refused.error.toString())
        assertAnUntrustedReadLoadsIt()
    }

    @Test
    fun `a reader's trusted read refuses a file others may write through a link naming the file and the link asked`() {
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        val reader = dir.reader().assertSuccess()
        val refused = Err(
            FileError.Refused(
                reader.root / "dotfiles" / "app.toml",
                real.info().assertSuccess(),
                RefusedBecause.OthersMayWrite,
                reader.root / "config" / "rel.toml",
            ),
        )

        assertEquals(refused, reader.readText("config/rel.toml", trusted = true))
        assertEquals(refused, reader.readBytes("config/rel.toml", trusted = true))
    }

    @Test
    fun `a private reader refuses a file the group may read through a link naming the file and the link asked`() {
        dir.chmod(0b111_000_000).assertSuccess()
        real.chmod(0b110_100_000).assertSuccess()
        if (!hasPermissions()) return
        val reader = dir.privateReader().assertSuccess()
        val refused = Err(
            FileError.Refused(
                reader.root / "dotfiles" / "app.toml",
                real.info().assertSuccess(),
                RefusedBecause.OthersMayRead,
                reader.root / "config" / "rel.toml",
            ),
        )

        assertEquals(refused, reader.readText("config/rel.toml"))
    }

    @Test
    fun `a reader's trusted read refuses a link into a folder the group may write naming that folder`() {
        dotfiles.chmod(0b111_111_000).assertSuccess()
        if (!hasPermissions()) return
        val reader = dir.reader().assertSuccess()
        val folder = reader.root / "dotfiles"
        val refused = Err(
            FileError.Refused(
                folder,
                folder.info().assertSuccess(),
                RefusedBecause.OthersMayWrite,
                reader.root / "config" / "rel.toml",
            ),
        )

        assertEquals(refused, reader.readText("config/rel.toml", trusted = true))
    }

    @Test
    fun `a private reader refuses a link into a folder the group may write naming that folder`() {
        dir.chmod(0b111_000_000).assertSuccess()
        dotfiles.chmod(0b111_111_000).assertSuccess()
        if (!hasPermissions()) return
        val reader = dir.privateReader().assertSuccess()
        val folder = reader.root / "dotfiles"

        val refused = reader.readText("config/rel.toml")

        val expected = FileError.Refused(
            folder,
            folder.info().assertSuccess(),
            RefusedBecause.OthersMayWrite,
            reader.root / "config" / "rel.toml",
        )
        assertEquals(Err(expected), refused)
    }

    @Test
    fun `a trusted read through a folder link names the real file it refused and the path asked`() {
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        val linked = links.make(dir / "linked", Path("dotfiles"))
        val refused = FileError.Refused(
            real.realPath().assertSuccess(),
            real.info().assertSuccess(),
            RefusedBecause.OthersMayWrite,
            linked / "app.toml",
        )

        assertEquals(Err(refused), (linked / "app.toml").readText(trusted = true))
    }

    @Test
    fun `a writer's trusted read through a folder link names the real file it refused and the name asked`() {
        dir.chmod(0b111_000_000).assertSuccess()
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        links.make(dir / "linked", Path("dotfiles"))
        val writer = dir.writer().assertSuccess()
        val refused = FileError.Refused(
            writer.root / "dotfiles" / "app.toml",
            real.info().assertSuccess(),
            RefusedBecause.OthersMayWrite,
            writer.root / "linked" / "app.toml",
        )

        assertEquals(Err(refused), writer.readText("linked/app.toml", trusted = true))
    }

    @Test
    fun `a trusted read refuses a plain file others may write naming it as the path asked`() {
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        val refused = Err(FileError.Refused(real, real.info().assertSuccess(), RefusedBecause.OthersMayWrite, real))

        assertEquals(refused, real.readText(trusted = true))
        assertEquals("Refused($real: OthersMayWrite, rw-rw-rw-)", refused.error.toString())
    }

    @Test
    fun `a trusted read refuses a link to a file in a folder the group may write`() {
        dotfiles.chmod(0b111_111_000).assertSuccess()
        if (!hasPermissions()) return
        val folder = dotfiles.realPath().assertSuccess()
        val refused = Err(FileError.Refused(folder, folder.info().assertSuccess(), RefusedBecause.OthersMayWrite, link))

        assertEquals(refused, link.readText(trusted = true))
        assertEquals(refused, link.readBytes(trusted = true))
        assertEquals("Refused($folder for $link: OthersMayWrite, rwxrwx---)", refused.error.toString())
        assertAnUntrustedReadLoadsIt()
    }

    @Test
    fun `a trusted read refuses a link that sits in a folder the group may write`() {
        config.chmod(0b111_111_000).assertSuccess()
        if (!hasPermissions()) return
        val refused = Err(FileError.Refused(config, config.info().assertSuccess(), RefusedBecause.OthersMayWrite, link))

        assertEquals(refused, link.readText(trusted = true))
        assertEquals(refused, link.readBytes(trusted = true))
        assertAnUntrustedReadLoadsIt()
    }

    @Test
    fun `accept is not asked about a file a trusted read refused`() {
        real.chmod(0b110_110_110).assertSuccess()
        if (!hasPermissions()) return
        var asked = false

        val refused = link.readText(trusted = true) {
            asked = true
            true
        }

        assertEquals(RefusedBecause.OthersMayWrite, (refused.errorOrNull() as FileError.Refused).why)
        assertFalse(asked)
    }

    @Test
    fun `accept still refuses a file a trusted read took`() {
        val refused = link.readText(trusted = true) { false }

        assertEquals(RefusedBecause.Accept, (refused.errorOrNull() as FileError.Refused).why)
    }
}
