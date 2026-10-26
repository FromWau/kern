package com.fromwau.kern.fs

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.errorOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LeftoversTest {
    private val dir = newTempDir()
    private val other = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
        other.deleteTree()
    }

    /** A scan that found [found] in the folder, nothing through a link, and failed on nothing. */
    private fun nothingFailed(found: List<Leftover>) =
        Ok(LeftoverScan(found, throughLinks = emptyList(), failed = emptyList(), passedOver = emptyList()))

    /** A scan that found [throughLinks] by following links, nothing in the folder itself, and failed on nothing. */
    private fun onlyThroughLinks(throughLinks: List<Leftover>) = Ok(
        LeftoverScan(found = emptyList(), throughLinks = throughLinks, failed = emptyList(), passedOver = emptyList()),
    )

    /**
     * Runs [block] with this folder at mode `444`, readable but not searchable, open again afterwards. [block] is
     * skipped where [entry] in it can still be looked up, as for root or on Windows, which leaves nothing to observe.
     */
    private fun Path.whileUnsearchable(
        entry: Path,
        block: () -> Unit,
    ) = withMode(0b100_100_100, stillWorks = { entry.fileType() is Result.Success }, block)

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    @Test
    fun `leftoversIn finds the temp of a file that does not exist`() {
        val temp = (dir / ".new.toml.$A_WRITE_ID.tmp").writeRaw("half")

        val expected = listOf(Leftover.Temp(file = dir / "new.toml", copy = temp, startedAt = A_WRITE_START))
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn finds the backup of a file that exists`() {
        (dir / "app.toml").writeRaw("a = 1")
        val backup = (dir / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 0")

        val expected = listOf(Leftover.Backup(file = dir / "app.toml", copy = backup, startedAt = A_WRITE_START))
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn reads a file name holding dots back whole`() {
        val temp = (dir / ".app.v2.toml.$A_WRITE_ID.tmp").writeRaw("half")

        val expected = listOf(Leftover.Temp(file = dir / "app.v2.toml", copy = temp, startedAt = A_WRITE_START))
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn finds a backup cut short as a partial one`() {
        val part = (dir / ".app.toml.$A_WRITE_ID.bak.part").writeRaw("a =")

        val expected = listOf(Leftover.PartialBackup(file = dir / "app.toml", copy = part, startedAt = A_WRITE_START))
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn passes over names no write spells`() {
        (dir / ".app.toml.${A_WRITE_ID.uppercase()}.tmp").writeRaw("x")
        (dir / ".app.toml.${A_WRITE_ID.replace("-", "")}.tmp").writeRaw("x")
        (dir / ".app.toml.before-my-edit.bak").writeRaw("x")
        (dir / ".app.toml.bak").writeRaw("x")
        (dir / "app.toml.$A_WRITE_ID.bak").writeRaw("x")
        (dir / "..$A_WRITE_ID.tmp").writeRaw("x")

        assertEquals(nothingFailed(emptyList()), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn lists the oldest write first and one write's copies in name order`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        val laterTemp = (dir / ".a.toml.${idStartedAt(later)}.tmp").writeRaw("x")
        val earlierId = idStartedAt(earlier)
        val earlierBackup = (dir / ".b.toml.$earlierId.bak").writeRaw("x")
        val earlierPart = (dir / ".b.toml.$earlierId.bak.part").writeRaw("x")

        val expected = listOf(
            Leftover.Backup(dir / "b.toml", earlierBackup, earlier),
            Leftover.PartialBackup(dir / "b.toml", earlierPart, earlier),
            Leftover.Temp(dir / "a.toml", laterTemp, later),
        )
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `a leftover whose id is no version 7 has no startedAt and is listed after the dated ones`() {
        val undated = (dir / ".a.toml.3a6c19aa-1f4e-4d2b-9c80-7e15b2c4d9f1.tmp").writeRaw("x")
        val dated = (dir / ".b.toml.$A_WRITE_ID.tmp").writeRaw("x")

        val expected = listOf(
            Leftover.Temp(dir / "b.toml", dated, A_WRITE_START),
            Leftover.Temp(dir / "a.toml", undated, startedAt = null),
        )
        assertEquals(nothingFailed(expected), dir.leftoversIn())
    }

    @Test
    fun `leftovers lists two killed writes oldest first and each started while it ran`() {
        val file = dir / "app.toml"

        val first = millisWhile { file.killedWrite() }
        val second = millisWhile { file.killedWrite() }

        val started = file
            .leftovers()
            .assertSuccess()
            .map { assertNotNull(it.startedAt).toEpochMilliseconds() }
        assertEquals(2, started.size)
        assertTrue(started[0] in first, "first write started at ${started[0]}, outside $first")
        assertTrue(started[1] in second, "second write started at ${started[1]}, outside $second")
    }

    /** An id of a write started at [time], spelled as a write spells its id. */
    @OptIn(ExperimentalUuidApi::class)
    private fun idStartedAt(time: Instant): Uuid = Uuid.generateV7NonMonotonicAt(time)

    /** Thrown from a write's content, which no write catches, so the write stops as if its process were killed. */
    private class Killed : Throwable()

    private fun Path.killedWrite() {
        assertFailsWith<Killed> { write(WriteMode.Auto) { throw Killed() } }
    }

    /** The milliseconds of the clock from just before [block] runs to just after. */
    private inline fun millisWhile(block: () -> Unit): LongRange {
        val before = Clock.System.now().toEpochMilliseconds()
        block()
        return before..Clock.System.now().toEpochMilliseconds()
    }

    @Test
    fun `leftoversIn of a file is NotADirectory`() {
        val file = (dir / "app.toml").writeRaw("a = 1")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.leftoversIn())
    }

    @Test
    fun `leftoversIn of a missing folder is NotFound`() {
        val missing = dir / "nope"

        assertEquals(Err(FileError.NotFound(missing)), missing.leftoversIn())
    }

    @Test
    fun `leftovers of a file gives its temp backup and partial backup each typed`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val backup = (dir / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 0")
        val part = (dir / ".app.toml.$A_WRITE_ID.bak.part").writeRaw("a =")
        val temp = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        (dir / ".other.toml.$A_WRITE_ID.tmp").writeRaw("b = 1")

        val expected = listOf(
            Leftover.Backup(file, backup, A_WRITE_START),
            Leftover.PartialBackup(file, part, A_WRITE_START),
            Leftover.Temp(file, temp, A_WRITE_START),
        )
        assertEquals(Ok(expected), file.leftovers())
    }

    @Test
    fun `leftovers of a missing file with only a temp gives that temp`() {
        val file = dir / "new.toml"
        val temp = (dir / ".new.toml.$A_WRITE_ID.tmp").writeRaw("half")

        assertEquals(Ok(listOf(Leftover.Temp(file, temp, A_WRITE_START))), file.leftovers())
    }

    private fun writer(): Writer = dir.writer().assertSuccess()

    @Test
    fun `a writer discards the copy of each kind and leaves the file`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val leftovers = listOf(
            Leftover.Backup(file, (dir / ".app.toml.$A_WRITE_ID.bak").writeRaw("x"), A_WRITE_START),
            Leftover.PartialBackup(file, (dir / ".app.toml.$A_WRITE_ID.bak.part").writeRaw("x"), A_WRITE_START),
            Leftover.Temp(file, (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("x"), A_WRITE_START),
        )

        assertEquals(List(3) { Ok(Unit) }, leftovers.map { writer().discard(it) })

        assertEquals(listOf(file), SystemFileSystem.list(dir).toList())
    }

    @Test
    fun `a writer discard of a copy already gone is NotFound as delete answers`() {
        val copy = dir / ".app.toml.$A_WRITE_ID.tmp"

        val discarded = writer().discard(Leftover.Temp(dir / "app.toml", copy, A_WRITE_START))

        assertEquals(Err(FileError.NotFound(copy)), discarded)
    }

    @Test
    fun `a writer restore puts the backup's content back and deletes the backup`() {
        val file = (dir / "app.toml").writeRaw("a = 1\nb =")
        val backup = (dir / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1\nb = 2")

        assertEquals(Ok(Unit), writer().restore(Leftover.Backup(file, backup, A_WRITE_START)))

        assertEquals("a = 1\nb = 2", file.readRaw())
        assertFalse(SystemFileSystem.exists(backup))
    }

    @Test
    fun `a writer restore creates a file that is missing`() {
        val file = dir / "app.toml"
        val backup = (dir / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")

        assertEquals(Ok(Unit), writer().restore(Leftover.Backup(file, backup, A_WRITE_START)))

        assertEquals("a = 1", file.readRaw())
        assertFalse(SystemFileSystem.exists(backup))
    }

    @Test
    fun `a writer restore gives a missing file the backup's permissions`() {
        val file = dir / "app.toml"
        val backup = dir / ".app.toml.$A_WRITE_ID.bak"
        backup.writeText("a = 1", ownerOnly = true).assertSuccess()

        assertEquals(Ok(Unit), writer().restore(Leftover.Backup(file, backup, A_WRITE_START)))

        file.assertPermissions(0b110_000_000)
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `a writer restore into a file that exists keeps that file's permissions`() {
        val file = (dir / "app.toml").writeRaw("a =")
        val mode = file.permissions().assertSuccess()
        val backup = dir / ".app.toml.$A_WRITE_ID.bak"
        backup.writeText("a = 1", ownerOnly = true).assertSuccess()

        assertEquals(Ok(Unit), writer().restore(Leftover.Backup(file, backup, A_WRITE_START)))

        assertEquals(Ok(mode), file.permissions())
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `a writer restore of a folder named like a backup is NotRegularFile naming it and leaves the file`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val backup = dir / ".app.toml.$A_WRITE_ID.bak"
        createDirectoriesRaw(backup)

        val restored = writer().restore(Leftover.Backup(file, backup, A_WRITE_START))

        assertEquals(Err(FileError.NotRegularFile(backup, FileType.Directory)), restored)
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `a writer restore of a backup already gone is NotFound naming it and leaves the file`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val backup = dir / ".app.toml.$A_WRITE_ID.bak"

        assertEquals(Err(FileError.NotFound(backup)), writer().restore(Leftover.Backup(file, backup, A_WRITE_START)))
        assertEquals("a = 1", file.readRaw())
    }

    /** The backup beside [file] in [other] that a scan of [dir] finds through the link `dir/app.toml`. */
    private fun backupThroughLink(file: Path): Leftover.Backup {
        (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1\nb = 2")
        (dir / "app.toml").linkTo(file)
        return assertIs<Leftover.Backup>(dir.leftoversIn().assertSuccess().throughLinks.single())
    }

    @Test
    fun `a writer restore of a backup found through a link is Escapes without throughLinks`() {
        val file = (other / "app.toml").writeRaw("a = 1\nb =")
        val backup = backupThroughLink(file)

        assertIs<FileError.Escapes>(writer().restore(backup).errorOrNull())
        assertEquals("a = 1\nb =", file.readRaw())
    }

    @Test
    fun `a writer restore with throughLinks puts a backup found through a link back and keeps the link a link`() {
        val file = (other / "app.toml").writeRaw("a = 1\nb =")
        val backup = backupThroughLink(file)

        assertEquals(Ok(Unit), writer().restore(backup, throughLinks = true))

        assertEquals("a = 1\nb = 2", file.readRaw())
        assertFalse(SystemFileSystem.exists(backup.copy))
        assertEquals(Ok(true), (dir / "app.toml").isSymlink())
    }

    @Test
    fun `a writer discard with throughLinks removes a copy found through a link`() {
        val file = (other / "app.toml").writeRaw("a = 1")
        val backup = backupThroughLink(file)

        assertIs<FileError.Escapes>(writer().discard(backup).errorOrNull())
        assertEquals(Ok(Unit), writer().discard(backup, throughLinks = true))

        assertFalse(SystemFileSystem.exists(backup.copy))
        assertEquals("a = 1", file.readRaw())
    }

    @Test
    fun `a writer restore with throughLinks refuses a folder others may write`() {
        val file = (other / "app.toml").writeRaw("a =")
        val backup = backupThroughLink(file)
        other.chmod(0b111_111_111).assertSuccess()
        try {
            if (other.permissions().assertSuccess() == null) return
            val refused = assertIs<FileError.Refused>(writer().restore(backup, throughLinks = true).errorOrNull())

            assertEquals(RefusedBecause.OthersMayWrite, refused.why)
            assertEquals(backup.copy, refused.asked)
            assertEquals("a =", file.readRaw())
        } finally {
            other.chmod(0b111_101_101).assertSuccess()
        }
    }

    @Test
    fun `a writer restore with throughLinks of a backup whose link is not below the root is Escapes`() {
        val file = (other / "app.toml").writeRaw("a =")
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val link = (other / "link.toml").linkTo(file)

        val restored = writer().restore(Leftover.Backup(file, copy, A_WRITE_START, via = link), throughLinks = true)

        assertIs<FileError.Escapes>(restored.errorOrNull())
        assertEquals("a =", file.readRaw())
    }

    @Test
    fun `a writer discard with throughLinks of a copy whose via is a plain file below the root is Escapes`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val plain = (dir / "app.toml").writeRaw("a =")

        val backup = Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = plain)

        val discarded = writer().discard(backup, throughLinks = true)

        assertIs<FileError.Escapes>(discarded.errorOrNull())
        assertEquals("a = 1", copy.readRaw())
        assertEquals("a =", plain.readRaw())
    }

    @Test
    fun `a writer discard with throughLinks of a copy whose via leads to another folder is Escapes`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val unrelated = other / "unrelated"
        createDirectoriesRaw(unrelated)
        val link = (dir / "app.toml").linkTo((unrelated / "app.toml").writeRaw("a ="))

        val backup = Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = link)

        val discarded = writer().discard(backup, throughLinks = true)

        assertIs<FileError.Escapes>(discarded.errorOrNull())
        assertEquals("a = 1", copy.readRaw())
    }

    @Test
    fun `a writer discard with throughLinks of a copy whose via is a link to another folder is Escapes`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val unrelated = other / "unrelated"
        createDirectoriesRaw(unrelated)
        val link = (dir / "conf").linkTo(unrelated)

        val backup = Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = link)

        val discarded = writer().discard(backup, throughLinks = true)

        assertIs<FileError.Escapes>(discarded.errorOrNull())
        assertEquals("a = 1", copy.readRaw())
    }

    @Test
    fun `a writer discard with throughLinks removes a copy in the folder its via link leads to`() {
        val copy = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 1")
        val link = (dir / "conf").linkTo(other)
        val backup = Leftover.Backup(other / "app.toml", copy, A_WRITE_START, via = link)

        assertEquals(Ok(Unit), writer().discard(backup, throughLinks = true))

        assertFalse(SystemFileSystem.exists(copy))
    }

    @Test
    fun `leftoversIn of an empty path is NotFound`() {
        assertEquals(Err(FileError.NotFound(Path(""))), Path("").leftoversIn())
    }

    @Test
    fun `leftoversIn finds through a link the temp a crashed write left beside the file it leads to`() {
        val file = (other / "app.toml").writeRaw("a = 1")
        val temp = (other / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        val link = (dir / "app.toml").linkTo(file)

        val scan = dir.leftoversIn().assertSuccess()
        val found = scan.throughLinks.single()

        assertEquals(emptyList(), scan.found)
        assertIs<Leftover.Temp>(found)
        assertEquals(link, found.via)
        assertEquals(file.realPath(), found.file.realPath())
        assertEquals(temp.realPath(), found.copy.realPath())
    }

    @Test
    fun `leftoversIn finds through a dangling link the temp of a first save`() {
        val file = other / "new.toml"
        val temp = (other / ".new.toml.$A_WRITE_ID.tmp").writeRaw("half")
        val link = (dir / "new.toml").linkTo(file)

        assertEquals(onlyThroughLinks(listOf(Leftover.Temp(file, temp, A_WRITE_START, via = link))), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn finds a leftover once when a link and its file share the folder`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val temp = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        (dir / "current.toml").linkTo(Path("app.toml"))

        assertEquals(nothingFailed(listOf(Leftover.Temp(file, temp, A_WRITE_START))), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn finds a leftover beside a file two links lead to once through the first link`() {
        val file = other / "new.toml"
        val temp = (other / ".new.toml.$A_WRITE_ID.tmp").writeRaw("half")
        val first = (dir / "a.toml").linkTo(file)
        (dir / "b.toml").linkTo(file)

        assertEquals(onlyThroughLinks(listOf(Leftover.Temp(file, temp, A_WRITE_START, via = first))), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn keeps a leftover in the folder apart from one found through a link`() {
        val inside = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        val file = other / "new.toml"
        val outside = (other / ".new.toml.$A_WRITE_ID.tmp").writeRaw("half")
        val link = (dir / "new.toml").linkTo(file)

        val expected = LeftoverScan(
            found = listOf(Leftover.Temp(dir / "app.toml", inside, A_WRITE_START)),
            throughLinks = listOf(Leftover.Temp(file, outside, A_WRITE_START, via = link)),
            failed = emptyList(),
            passedOver = emptyList(),
        )
        assertEquals(Ok(expected), dir.leftoversIn())
    }

    @Test
    fun `oldestBackups names each file's earliest backup and passes over the other kinds`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        (dir / ".a.toml.${idStartedAt(later)}.bak").writeRaw("a1")
        val earlierOfA = (dir / ".a.toml.${idStartedAt(earlier)}.bak").writeRaw("a0")
        val onlyOfB = (dir / ".b.toml.${idStartedAt(later)}.bak").writeRaw("b0")
        (dir / ".c.toml.${idStartedAt(earlier)}.tmp").writeRaw("c")

        val expected = mapOf(
            dir / "a.toml" to Leftover.Backup(dir / "a.toml", earlierOfA, earlier),
            dir / "b.toml" to Leftover.Backup(dir / "b.toml", onlyOfB, later),
        )
        assertEquals(expected, dir.leftoversIn().assertSuccess().oldestBackups())
    }

    @Test
    fun `oldestBackups takes the first by name of two backups started in one millisecond`() {
        val second = Leftover.Backup(dir / "a.toml", dir / ".a.toml.b.bak", A_WRITE_START)
        val first = Leftover.Backup(dir / "a.toml", dir / ".a.toml.a.bak", A_WRITE_START)
        val undated = Leftover.Backup(dir / "a.toml", dir / ".a.toml.0.bak", startedAt = null)
        val scan = LeftoverScan(listOf(undated, second, first), emptyList(), emptyList(), emptyList())

        assertEquals(mapOf(dir / "a.toml" to first), scan.oldestBackups())
    }

    @Test
    fun `oldestBackups names the backup of a file a link leads to only when asked through links`() {
        val file = (other / "app.toml").writeRaw("a = 1")
        val backup = (other / ".app.toml.$A_WRITE_ID.bak").writeRaw("a = 0")
        val link = (dir / "app.toml").linkTo(file)
        val scan = dir.leftoversIn().assertSuccess()
        val throughLink = assertIs<Leftover.Backup>(scan.throughLinks.single())

        assertEquals(emptyMap(), scan.oldestBackups())
        assertEquals(mapOf(throughLink.file to throughLink), scan.oldestBackups(throughLinks = true))
        assertEquals(backup.realPath(), throughLink.copy.realPath())
        assertEquals(link, throughLink.via)
    }

    @Test
    fun `oldestBackups through links names a file reached directly and through a link once by its oldest backup`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        val file = (other / "app.toml").writeRaw("a = 1")
        (other / ".app.toml.${idStartedAt(earlier)}.bak").writeRaw("a = 0")
        (dir / ".app.toml.${idStartedAt(later)}.bak").writeRaw("a = -1")
        (dir / "app.toml").linkTo(file)
        val scan = dir.leftoversIn().assertSuccess()
        val throughLink = assertIs<Leftover.Backup>(scan.throughLinks.single())

        assertEquals(1, scan.found.size)
        assertEquals(mapOf(throughLink.file to throughLink), scan.oldestBackups(throughLinks = true))
    }

    @Test
    fun `an unnamed temp names no file`() {
        assertEquals(null, Leftover.UnnamedTemp(dir / "copy", A_WRITE_START).file)
    }

    @Test
    fun `byFile groups each file's leftovers oldest first and leaves out an unnamed temp`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        val laterBackup = (dir / ".a.toml.${idStartedAt(later)}.bak").writeRaw("a1")
        val earlierTemp = (dir / ".a.toml.${idStartedAt(earlier)}.tmp").writeRaw("a0")
        val ofB = (dir / ".b.toml.${idStartedAt(earlier)}.bak.part").writeRaw("b")
        (dir / ".${idStartedAt(earlier)}.tmp").writeRaw("unnamed")

        val expected = mapOf(
            dir / "a.toml" to listOf(
                Leftover.Temp(dir / "a.toml", earlierTemp, earlier),
                Leftover.Backup(dir / "a.toml", laterBackup, later),
            ),
            dir / "b.toml" to listOf(Leftover.PartialBackup(dir / "b.toml", ofB, earlier)),
        )
        val grouped = dir.leftoversIn().assertSuccess().byFile()

        assertEquals(expected, grouped)
        assertEquals(listOf(dir / "a.toml", dir / "b.toml"), grouped.keys.toList())
    }

    @Test
    fun `byFile lists the file whose oldest leftover is oldest first`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        (dir / ".a.toml.${idStartedAt(later)}.bak").writeRaw("a")
        (dir / ".b.toml.${idStartedAt(earlier)}.bak").writeRaw("b")

        assertEquals(listOf(dir / "b.toml", dir / "a.toml"), dir.leftoversIn().assertSuccess().byFile().keys.toList())
    }

    @Test
    fun `byFile groups a file's copies found directly and through a link only when asked through links`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        val file = (other / "app.toml").writeRaw("a = 1")
        (other / ".app.toml.${idStartedAt(earlier)}.bak").writeRaw("a = 0")
        (dir / ".app.toml.${idStartedAt(later)}.tmp").writeRaw("a = 2")
        (dir / "app.toml").linkTo(file)
        val scan = dir.leftoversIn().assertSuccess()
        val throughLink = assertIs<Leftover.Backup>(scan.throughLinks.single())
        val direct = scan.found.single()

        assertEquals(mapOf(dir / "app.toml" to listOf(direct)), scan.byFile())
        assertEquals(mapOf(throughLink.file to listOf(throughLink, direct)), scan.byFile(throughLinks = true))
    }

    @Test
    fun `byFile keeps a staged link apart from the file the link leads to`() {
        val target = (dir / "target.toml").writeRaw("a = 1")
        val current = (dir / "current").linkTo(Path("target.toml"))
        val staged = Leftover.StagedLink(current, dir / ".current.$A_WRITE_ID.link", A_WRITE_START)
        val temp = Leftover.Temp(target, dir / ".target.toml.$A_WRITE_ID.tmp", A_WRITE_START)
        val scan = LeftoverScan(listOf(staged, temp), emptyList(), emptyList(), emptyList())

        assertEquals(mapOf(current to listOf(staged), target to listOf(temp)), scan.byFile())
    }

    @Test
    fun `oldestBackups through links keys a file as byFile keys it`() {
        val earlier = Instant.fromEpochMilliseconds(1_000)
        val later = Instant.fromEpochMilliseconds(2_000)
        val file = (other / "app.toml").writeRaw("a = 1")
        (other / ".app.toml.${idStartedAt(later)}.bak").writeRaw("a = 0")
        (dir / ".app.toml.${idStartedAt(earlier)}.tmp").writeRaw("a = 2")
        (dir / "app.toml").linkTo(file)
        val scan = dir.leftoversIn().assertSuccess()
        val throughLink = assertIs<Leftover.Backup>(scan.throughLinks.single())

        assertEquals(listOf(dir / "app.toml"), scan.byFile(throughLinks = true).keys.toList())
        assertEquals(mapOf(dir / "app.toml" to throughLink), scan.oldestBackups(throughLinks = true))
    }

    @Test
    fun `only a backup says the file it was for may be damaged`() {
        val file = dir / "a.toml"
        val copy = dir / "copy"
        val mayBeDamaged = mapOf(
            Leftover.Backup(file, copy, A_WRITE_START) to true,
            Leftover.PartialBackup(file, copy, A_WRITE_START) to false,
            Leftover.Temp(file, copy, A_WRITE_START) to false,
            Leftover.UnnamedTemp(copy, A_WRITE_START) to false,
            Leftover.StagedFolder(file, copy, A_WRITE_START) to false,
            Leftover.StagedLink(file, copy, A_WRITE_START) to false,
        )

        assertEquals(mayBeDamaged, mayBeDamaged.keys.associateWith { it.fileMayBeDamaged })
    }

    @Test
    fun `a stage claim with no stage beside it is not listed`() {
        (dir / ".out.${idStartedAt(A_WRITE_START)}.lock").writeRaw("")

        assertEquals(Ok(LeftoverScan.EMPTY), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn of a missing folder reads as EMPTY through orIfNotFound`() {
        assertEquals(Ok(LeftoverScan.EMPTY), (dir / "missing").leftoversIn().orIfNotFound { LeftoverScan.EMPTY })
        assertEquals(LeftoverScan(emptyList(), emptyList(), emptyList(), emptyList()), LeftoverScan.EMPTY)
    }

    @Test
    fun `leftoversIn does not look into a folder a link in it leads to`() {
        (other / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        (dir / "linked").linkTo(other)

        assertEquals(nothingFailed(emptyList()), dir.leftoversIn())
    }

    @Test
    fun `leftovers of a link names the link as the way to them and leftovers of a file names none`() {
        val file = (other / "app.toml").writeRaw("a = 1")
        (other / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        val link = (dir / "app.toml").linkTo(file)

        assertEquals(listOf(link), link.leftovers().assertSuccess().map { it.via })
        assertEquals(listOf(null), file.leftovers().assertSuccess().map { it.via })
    }

    /** `a.md` in `other/work` and `b.md` in `other/work/sub`, each with a backup, and `dir/work` linked there. */
    private fun linkedWorkFolder(): Path {
        createDirectoriesRaw(other / "work" / "sub")
        (other / "work" / "a.md").writeRaw("a =")
        (other / "work" / ".a.md.$A_WRITE_ID.bak").writeRaw("a = 1")
        (other / "work" / "sub" / "b.md").writeRaw("b =")
        (other / "work" / "sub" / ".b.md.$A_WRITE_ID.bak").writeRaw("b = 1")
        return (dir / "work").linkTo(other / "work")
    }

    @Test
    fun `leftoversIn of a folder that is a link names that link as the way to the copies directly in it`() {
        val work = linkedWorkFolder()

        val backup = assertIs<Leftover.Backup>(work.leftoversIn().assertSuccess().found.single())

        assertEquals(work, backup.via)
        assertEquals(listOf(work), (work / "a.md").leftovers().assertSuccess().map { it.via })
        assertEquals(Ok(Unit), writer().restore(backup, throughLinks = true))
        assertEquals("a = 1", (other / "work" / "a.md").readRaw())
    }

    @Test
    fun `leftoversIn of a folder that is a link names no way to the copies in a folder below it`() {
        val work = linkedWorkFolder()

        val found = work.leftoversIn(depth = 1).assertSuccess().found

        assertEquals(mapOf<String?, Path?>("a.md" to work, "b.md" to null), found.associate { it.file?.name to it.via })
    }

    @Test
    fun `leftoversIn of a folder that may be listed puts a link it cannot check into failed and scans on`() {
        val temp = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        val loop = (dir / "loop.toml").linkTo(Path("loop.toml"))

        val scan = dir.leftoversIn().assertSuccess()

        assertEquals(listOf(Leftover.Temp(dir / "app.toml", temp, A_WRITE_START)), scan.found)
        assertEquals(listOf(loop), scan.failed.map { it.path })
        assertIs<FileError.Inaccessible>(scan.failed.single().error)
    }

    @Test
    fun `a write that succeeds leaves the leftovers of earlier writes`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val temp = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 0")

        file.writeText("a = 2", WriteMode.InPlace).assertSuccess()
        file.writeText("a = 3", WriteMode.Replace).assertSuccess()

        assertEquals(Ok(listOf(Leftover.Temp(file, temp, A_WRITE_START, via = null))), file.leftovers())
    }

    /** One leftover at the root, one in `sub` and one in `sub/deeper`, each started a second after the next. */
    private fun threeLevelsOfLeftovers(): List<Leftover> {
        createDirectoriesRaw(dir / "sub" / "deeper")
        val deep = Instant.fromEpochMilliseconds(1_000)
        val middle = Instant.fromEpochMilliseconds(2_000)
        val top = Instant.fromEpochMilliseconds(3_000)
        return listOf(
            Leftover.Backup(
                dir / "sub" / "deeper" / "c.toml",
                (dir / "sub" / "deeper" / ".c.toml.${idStartedAt(deep)}.bak").writeRaw("x"),
                deep,
            ),
            Leftover.Temp(
                dir / "sub" / "b.toml",
                (dir / "sub" / ".b.toml.${idStartedAt(middle)}.tmp").writeRaw("x"),
                middle,
            ),
            Leftover.Temp(dir / "a.toml", (dir / ".a.toml.${idStartedAt(top)}.tmp").writeRaw("x"), top),
        )
    }

    @Test
    fun `leftoversIn with depth 0 reads the folder alone`() {
        val (_, _, top) = threeLevelsOfLeftovers()

        assertEquals(nothingFailed(listOf(top)), dir.leftoversIn(depth = 0))
        assertEquals(nothingFailed(listOf(top)), dir.leftoversIn())
    }

    @Test
    fun `leftoversIn with depth 1 reads the folder and each folder directly in it`() {
        val (_, middle, top) = threeLevelsOfLeftovers()

        assertEquals(nothingFailed(listOf(middle, top)), dir.leftoversIn(depth = 1))
    }

    @Test
    fun `leftoversIn with depth Int MAX_VALUE reads every folder below and lists them oldest first`() {
        val expected = threeLevelsOfLeftovers()

        assertEquals(nothingFailed(expected), dir.leftoversIn(depth = Int.MAX_VALUE))
    }

    @Test
    fun `leftoversIn of a negative depth is an IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { dir.leftoversIn(depth = -1) }
    }

    @Test
    fun `leftoversIn does not enter a folder a link leads to and lists the link as passed over`() {
        (other / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        createDirectoriesRaw(dir / "sub")
        val link = (dir / "sub" / "linked").linkTo(other)
        (dir / "file.toml").linkTo((other / "file.toml").writeRaw("f"))

        val expected = LeftoverScan(emptyList(), emptyList(), emptyList(), passedOver = listOf(link))
        assertEquals(Ok(expected), dir.leftoversIn(depth = Int.MAX_VALUE))
    }

    @Test
    fun `leftoversIn with depth 1 passes over a link to a folder at the root and not one a level below`() {
        (other / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        createDirectoriesRaw(dir / "sub")
        val atRoot = (dir / "linked").linkTo(other)
        (dir / "sub" / "linked").linkTo(other)

        val expected = LeftoverScan(emptyList(), emptyList(), emptyList(), passedOver = listOf(atRoot))
        assertEquals(Ok(expected), dir.leftoversIn(depth = 1))
        assertEquals(nothingFailed(emptyList()), dir.leftoversIn(depth = 0))
    }

    @Test
    fun `leftovers of an app lists a folder link below it as passed over once`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess().let { it.copy(data = it.config) }
        createDirectoriesRaw(app.config)
        val link = (app.config / "linked").linkTo(other)

        assertEquals(Ok(LeftoverScan(emptyList(), emptyList(), emptyList(), listOf(link))), app.leftovers())
    }

    @Test
    fun `leftoversIn with unlimited depth lists a leftover a link in a folder below leads back to once in found`() {
        val file = (dir / "app.toml").writeRaw("a = 1")
        val temp = (dir / ".app.toml.$A_WRITE_ID.tmp").writeRaw("a = 2")
        createDirectoriesRaw(dir / "sub")
        (dir / "sub" / "app.toml").linkTo(file)

        val expected = nothingFailed(listOf(Leftover.Temp(file, temp, A_WRITE_START)))
        assertEquals(expected, dir.leftoversIn(depth = Int.MAX_VALUE))
    }

    @Test
    fun `leftoversIn with unlimited depth puts a folder below it cannot list into failed and scans the others`() {
        val locked = dir / "notes" / "locked"
        createDirectoriesRaw(locked)
        createDirectoriesRaw(dir / "notes" / "ok")
        val temp = (dir / "notes" / "ok" / ".a.md.$A_WRITE_ID.tmp").writeRaw("x")

        locked.whileUnlistable {
            val expected = LeftoverScan(
                found = listOf(Leftover.Temp(dir / "notes" / "ok" / "a.md", temp, A_WRITE_START)),
                throughLinks = emptyList(),
                failed = listOf(ScanFailure(locked, FileError.Inaccessible(locked, IoCause.Denied, null))),
                passedOver = emptyList(),
            )
            assertEquals(Ok(expected), dir.leftoversIn(depth = Int.MAX_VALUE))
        }
    }

    @Test
    fun `leftoversIn with unlimited depth puts an entry whose type it cannot look up into failed`() {
        val notes = dir / "notes"
        val sub = notes / "sub"
        createDirectoriesRaw(sub)
        (sub / ".a.md.$A_WRITE_ID.tmp").writeRaw("x")

        notes.whileUnsearchable(sub) {
            val failed = listOf(ScanFailure(sub, FileError.Inaccessible(sub, IoCause.Denied, null)))
            val expected = LeftoverScan(emptyList(), emptyList(), failed, emptyList())
            assertEquals(Ok(expected), dir.leftoversIn(depth = Int.MAX_VALUE))
        }
    }

    @Test
    fun `leftoversIn puts an entry it cannot tell from a link into failed`() {
        val notes = dir / "notes"
        val entry = (notes / "app.toml").also { createDirectoriesRaw(notes) }.writeRaw("a = 1")

        notes.whileUnsearchable(entry) {
            val failed = listOf(ScanFailure(entry, FileError.Inaccessible(entry, IoCause.Denied, null)))
            assertEquals(Ok(LeftoverScan(emptyList(), emptyList(), failed, emptyList())), notes.leftoversIn())
        }
    }

    @Test
    fun `leftoversIn of a folder it cannot list is the call's error`() {
        dir.whileUnlistable {
            assertEquals(Err(FileError.Inaccessible(dir, IoCause.Denied, null)), dir.leftoversIn(depth = Int.MAX_VALUE))
        }
    }

    @Test
    fun `leftovers of an app puts a folder below it cannot list into failed and scans on`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess()
        val locked = app.state / "locked"
        createDirectoriesRaw(locked)
        createDirectoriesRaw(app.config)
        val temp = (app.config / ".app.toml.$A_WRITE_ID.tmp").writeRaw("x")

        locked.whileUnlistable {
            val expected = LeftoverScan(
                found = listOf(Leftover.Temp(app.config / "app.toml", temp, A_WRITE_START)),
                throughLinks = emptyList(),
                failed = listOf(ScanFailure(locked, FileError.Inaccessible(locked, IoCause.Denied, null))),
                passedOver = emptyList(),
            )
            assertEquals(Ok(expected), app.leftovers())
        }
    }

    @Test
    fun `leftovers of an app scans config data state and runtime whole and passes over a missing one`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess()
        createDirectoriesRaw(app.config)
        createDirectoriesRaw(app.state / "sub")
        createDirectoriesRaw(app.cache)
        val config = (app.config / ".app.toml.$A_WRITE_ID.tmp").writeRaw("x")
        val state = (app.state / "sub" / ".db.$A_WRITE_ID.bak").writeRaw("x")
        (app.cache / ".page.$A_WRITE_ID.tmp").writeRaw("x")

        val expected = listOf(
            Leftover.Temp(app.config / "app.toml", config, A_WRITE_START),
            Leftover.Backup(app.state / "sub" / "db", state, A_WRITE_START),
        )
        assertEquals(nothingFailed(expected), app.leftovers())
    }

    @Test
    fun `leftovers of an app whose config and data are one folder lists each leftover once`() {
        val app = BaseDirs.under(dir).forApp("app").assertSuccess().let { it.copy(data = it.config) }
        createDirectoriesRaw(app.config)
        val temp = (app.config / ".app.toml.$A_WRITE_ID.tmp").writeRaw("x")

        val expected = listOf(Leftover.Temp(app.config / "app.toml", temp, A_WRITE_START))
        assertEquals(nothingFailed(expected), app.leftovers())
    }

    @Test
    fun `a shared writer scans and restores a backup in a private folder below its root`() {
        val secrets = (dir / "secrets").also { createDirectoriesRaw(it) }
        secrets.chmod(0b111_000_000).assertSuccess()
        val token = (secrets / "token").writeRaw("half")
        token.chmod(0b110_000_000).assertSuccess()
        val copy = (secrets / ".token.$A_WRITE_ID.bak").writeRaw("whole")
        copy.chmod(0b110_100_000).assertSuccess()
        val writer = dir.writer().assertSuccess()

        val backup = writer.leftovers(depth = Int.MAX_VALUE).assertSuccess().oldestBackups().values.single()
        writer.restore(backup).assertSuccess()

        assertEquals("whole", token.readRaw())
        if (token.permissions().assertSuccess() != null) token.assertPermissions(0b110_000_000)
        assertEquals(listOf(token), secrets.list().assertSuccess().map { Path(secrets, it.name) })
    }
}
