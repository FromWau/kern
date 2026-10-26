package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.Leftover
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.leftovers
import com.fromwau.kern.fs.leftoversIn
import com.fromwau.kern.fs.readText
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LeftoverKindTest {
    private val dir = scratchDir()
    private val file = dir / "app.toml"

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    /**
     * The one leftover a scan of [dir] finds once [kind] was left beside [file], checked to be a [type] at its path.
     */
    @IgnorableReturnValue
    private fun assertFoundAs(
        kind: LeftoverKind,
        type: KClass<out Leftover>,
        content: String = "",
    ): Leftover {
        val copy = file.leaveLeftover(kind, content)

        val found = dir.leftoversIn().assertSuccess().found.single()

        assertEquals(type, found::class)
        assertEquals(copy, found.copy)
        return found
    }

    @Test
    fun `a Temp left beside a file is found as a Temp of that file holding the content`() {
        val found = assertIs<Leftover.Temp>(assertFoundAs(LeftoverKind.Temp, Leftover.Temp::class, content = "new"))

        assertEquals(file, found.file)
        assertEquals(Ok("new"), found.copy.readText())
    }

    @Test
    fun `an UnnamedTemp is found as one`() {
        assertFoundAs(LeftoverKind.UnnamedTemp, Leftover.UnnamedTemp::class)
    }

    @Test
    fun `a Backup is found as one of the file`() {
        val found = assertIs<Leftover.Backup>(assertFoundAs(LeftoverKind.Backup, Leftover.Backup::class))

        assertEquals(file, found.file)
    }

    @Test
    fun `a PartialBackup is found as one`() {
        assertFoundAs(LeftoverKind.PartialBackup, Leftover.PartialBackup::class)
    }

    @Test
    fun `a StagedFolder is found as one`() {
        assertFoundAs(LeftoverKind.StagedFolder, Leftover.StagedFolder::class)
    }

    @Test
    fun `a StagedLink is found as one`() {
        assertFoundAs(LeftoverKind.StagedLink, Leftover.StagedLink::class)
    }

    @Test
    fun `each leftover left beside one file has a name of its own`() {
        val copies = LeftoverKind.entries.map { file.leaveLeftover(it) }

        assertEquals(copies.toSet(), dir.leftoversIn().assertSuccess().found.map { it.copy }.toSet())
    }

    @Test
    fun `each leftover left beside a file in a missing folder makes the folder and is found there`() {
        for (kind in LeftoverKind.entries) {
            val folder = dir / "new-$kind"

            val copy = (folder / "f").leaveLeftover(kind)

            assertEquals(listOf(copy), folder.leftoversIn().assertSuccess().found.map { it.copy }, "$kind")
        }
    }

    private val target = dir / "real" / "app.toml"
    private val link = dir / "links" / "app.toml"

    /** [link], a symlink to [target], which holds content. */
    private fun linkToTarget() {
        target.writeText("port = 2").assertSuccess()
        (dir / "links").createDirectories().assertSuccess()
        link.symlinkTo(target).assertSuccess()
    }

    @Test
    fun `a Temp left at a link goes beside the file the link leads to as a write's temp does`() {
        linkToTarget()

        val copy = link.leaveLeftover(LeftoverKind.Temp)

        assertEquals(listOf(copy), target.leftovers().assertSuccess().map { it.copy })
        assertEquals(emptyList(), (dir / "links").leftoversIn().assertSuccess().found)
    }

    @Test
    fun `a Backup and a PartialBackup left at a link go beside the file the link leads to`() {
        linkToTarget()

        val copies = listOf(LeftoverKind.Backup, LeftoverKind.PartialBackup).map { link.leaveLeftover(it) }

        assertEquals(copies.toSet(), target.leftovers().assertSuccess().map { it.copy }.toSet())
        assertEquals(emptyList(), (dir / "links").leftoversIn().assertSuccess().found)
    }

    @Test
    fun `an UnnamedTemp left at a link goes beside the file the link leads to`() {
        linkToTarget()

        val copy = link.leaveLeftover(LeftoverKind.UnnamedTemp)

        assertEquals(listOf(copy), (dir / "real").leftoversIn().assertSuccess().found.map { it.copy })
        assertEquals(emptyList(), (dir / "links").leftoversIn().assertSuccess().found)
    }

    @Test
    fun `a StagedFolder left at a link goes beside the link as a folder build stages it`() {
        linkToTarget()

        val copy = link.leaveLeftover(LeftoverKind.StagedFolder)

        assertEquals(listOf(copy), (dir / "links").leftoversIn().assertSuccess().found.map { it.copy })
        assertEquals(emptyList(), (dir / "real").leftoversIn().assertSuccess().found)
    }

    @Test
    fun `a StagedLink left at a link goes beside the link as a switch stages it`() {
        linkToTarget()

        val copy = link.leaveLeftover(LeftoverKind.StagedLink)

        assertEquals(listOf(copy), (dir / "links").leftoversIn().assertSuccess().found.map { it.copy })
    }

    @Test
    fun `the README example finds the backup it left beside the config`() {
        val config = dir / "app.toml"
        config.writeText("port = 2").assertSuccess()
        val backup = config.leaveLeftover(LeftoverKind.Backup, content = "port = 1")

        val found = dir.leftoversIn().assertSuccess().found.single()

        assertEquals(Leftover.Backup(config, backup, found.startedAt), found)
    }
}
