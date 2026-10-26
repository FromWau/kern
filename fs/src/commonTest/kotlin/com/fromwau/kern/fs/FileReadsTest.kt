package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.hardLinkTo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class FileReadsTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `info counts the hard links of a file where the platform counts them`() {
        val file = (dir / "a.txt").writeRaw("a")
        val links = file.info().assertSuccess().links ?: return
        assertEquals(1, links)

        (dir / "b.txt").hardLinkTo(file).assertSuccess()

        assertEquals(2, file.info().assertSuccess().links)
        assertEquals(2, (dir / "b.txt").info().assertSuccess().links)
    }

    @Test
    fun `orNullIfNotFound turns NotFound into null and leaves other answers alone`() {
        val missing = dir / "missing.txt"
        val file = (dir / "a.txt").writeRaw("a")
        val other = Err(FileError.NotADirectory(file, FileType.Regular))

        assertEquals(Ok(null), missing.readText().orNullIfNotFound())
        assertEquals(Ok("a"), file.readText().orNullIfNotFound())
        assertEquals(other, other.orNullIfNotFound())
    }

    @Test
    fun `a file gone after it was looked up reads as NotFound`() {
        val missing = dir / "gone.txt"

        assertEquals(Err(FileError.NotFound(missing)), missing.readWhole(maxBytes = null))
    }

    @Test
    fun `a path seen as neither file nor folder by a lookup that missed its deletion is NotFound`() {
        val gone = dir / "gone.txt"

        assertEquals(Err(FileError.NotFound(gone)), gone.metadata(lookUp = { tornLookup }))
    }

    @Test
    fun `a folder or file seen as neither by a lookup that missed a change is what is there now`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        val file = (dir / "a.txt").writeRaw("a")

        assertTrue(folder.metadata(lookUp = { tornLookup }).assertSuccess().isDirectory)
        assertTrue(file.metadata(lookUp = { tornLookup }).assertSuccess().isRegularFile)
    }

    @Test
    fun `exists is true for a file and false for a missing path`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertTrue(file.exists())
        assertFalse((dir / "missing").exists())
    }

    @Test
    fun `readText returns the file content`() {
        val file = (dir / "a.txt").writeRaw("héllo")

        assertEquals(Ok("héllo"), file.readText())
    }

    @Test
    fun `readBytes returns the file's bytes as they are`() {
        val file = dir / "a.bin"
        val bytes = byteArrayOf(0x68, 0xFF.toByte(), 0x69, 0x00)
        SystemFileSystem
            .sink(file)
            .buffered()
            .use { it.write(bytes) }

        assertContentEquals(bytes, file.readBytes().assertSuccess())
    }

    @Test
    fun `readBytes refuses a file larger than the limit`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Err(FileError.TooLarge(file, limitBytes = 4, atLeastBytes = 5)), file.readBytes(maxBytes = 4))
    }

    @Test
    fun `readBytes of a directory is NotRegularFile`() {
        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readBytes())
    }

    @Test
    fun `fileType tells a file from a folder`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Ok(FileType.Regular), file.fileType())
        assertEquals(Ok(FileType.Directory), dir.fileType())
        assertEquals(Err(FileError.NotFound(dir / "missing")), (dir / "missing").fileType())
    }

    @Test
    fun `fileSize is the file's length and refuses a folder`() {
        val file = (dir / "a.txt").writeRaw("héllo")

        assertEquals(Ok(6L), file.fileSize())
        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.fileSize())
    }

    @Test
    fun `readText of a missing file is NotFound`() {
        val file = dir / "missing"

        assertEquals(Err(FileError.NotFound(file)), file.readText())
    }

    @Test
    fun `readText of a directory is NotRegularFile`() {
        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readText())
    }

    @Test
    fun `readText refuses a file larger than the limit`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Err(FileError.TooLarge(file, limitBytes = 4, atLeastBytes = 5)), file.readText(maxBytes = 4))
    }

    @Test
    fun `readText accepts a file exactly at the limit`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Ok("12345"), file.readText(maxBytes = 5))
    }

    @Test
    fun `readText with the largest limit reads the whole file`() {
        val file = (dir / "a.txt").writeRaw("12345")

        assertEquals(Ok("12345"), file.readText(maxBytes = Long.MAX_VALUE))
    }

    @Test
    fun `readText decodes bytes that are not UTF-8 as the replacement character`() {
        val file = dir / "a.bin"
        SystemFileSystem
            .sink(file)
            .buffered()
            .use { it.write(byteArrayOf(0x68, 0xFF.toByte(), 0x69)) }

        assertEquals(Ok("h�i"), file.readText())
    }

    @Test
    fun `list gives the entries in name order`() {
        (dir / "b.txt").writeRaw("b")
        (dir / "a.txt").writeRaw("a")
        createDirectoriesRaw(dir / "c")

        assertEquals(Ok(listOf(dir / "a.txt", dir / "b.txt", dir / "c")), dir.list())
    }

    @Test
    fun `list gives nothing for an empty directory`() {
        val empty = dir / "empty"
        createDirectoriesRaw(empty)

        assertEquals(Ok(emptyList<Path>()), empty.list())
    }

    @Test
    fun `list refuses a file`() {
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(Err(FileError.NotADirectory(file, FileType.Regular)), file.list())
    }

    @Test
    fun `a path that runs through a file is NotFound`() {
        val file = (dir / "plain.txt").writeRaw("x")
        val throughIt = file / "child.toml"

        assertEquals(Err(FileError.NotFound(throughIt)), throughIt.readText())
    }

    @Test
    fun `list reports a missing path as NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.list())
    }

    @Test
    fun `modifiedAt is when the file was last written`() {
        val before = Clock.System.now() - 2.seconds
        val file = (dir / "a.txt").writeRaw("a")
        val after = Clock.System.now() + 2.seconds

        val modified = file.modifiedAt().assertSuccess()

        assertTrue(modified in before..after, "$modified is not between $before and $after")
    }

    @Test
    fun `modifiedAt of a missing path is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.modifiedAt())
    }

    @Test
    fun `realPath of a file spelled with a dot segment is the one absolute name it has`() {
        val file = (dir / "a.txt").writeRaw("a")

        val real = (dir / "." / "a.txt").realPath().assertSuccess()

        assertTrue(real.isAbsolute)
        assertEquals(SystemFileSystem.resolve(file), real)
    }

    @Test
    fun `realPath of a path nothing is at is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.realPath())
    }

    @Test
    fun `orIfNotFound turns NotFound into the value it is given`() {
        val missing = dir / "missing.toml"

        assertEquals(Ok("theme = light"), missing.readText().orIfNotFound { "theme = light" })
    }

    @Test
    fun `orIfNotFound leaves other errors and successes as they are and asks for no value then`() {
        val folder = dir / "folder"
        createDirectoriesRaw(folder)
        val file = (dir / "a.txt").writeRaw("a")

        assertEquals(
            Err(FileError.NotRegularFile(folder, FileType.Directory)),
            folder.readText().orIfNotFound { error("asked for a value") },
        )
        assertEquals(Ok("a"), file.readText().orIfNotFound { error("asked for a value") })
    }

    @Test
    fun `permissions of a file are its mode bits where the platform has them and read the way ls shows them`() {
        val file = dir / "a.txt"
        file.writeText("a", ownerOnly = true).assertSuccess()

        file.assertPermissions(0b110_000_000)
        assertEquals("rw-------", Permissions(0b110_000_000).toString())
        assertEquals("rwxr-xr-x", Permissions(0b111_101_101).toString())
        assertEquals("rwsr-sr-T", Permissions(0b111_111_101_100).toString())
        assertEquals("rwSrwsr-t", Permissions(0b111_110_111_101).toString())
    }

    @Test
    fun `permissions of a missing path is NotFound`() {
        val missing = dir / "missing"

        assertEquals(Err(FileError.NotFound(missing)), missing.permissions())
    }

    @Test
    fun `readPrefix reads at most maxBytes from the start of the file`() {
        val file = (dir / "a.txt").writeRaw("abcdef")

        assertContentEquals("abc".encodeToByteArray(), file.readPrefix(maxBytes = 3).assertSuccess())
    }

    @Test
    fun `readPrefix of a file shorter than maxBytes reads all of it`() {
        val file = (dir / "a.txt").writeRaw("ab")

        assertContentEquals("ab".encodeToByteArray(), file.readPrefix(maxBytes = 3).assertSuccess())
        assertContentEquals("ab".encodeToByteArray(), file.readPrefix(maxBytes = Long.MAX_VALUE).assertSuccess())
    }

    @Test
    fun `readPrefix of zero or fewer bytes reads nothing`() {
        val file = (dir / "a.txt").writeRaw("ab")

        assertContentEquals(ByteArray(0), file.readPrefix(maxBytes = 0).assertSuccess())
        assertContentEquals(ByteArray(0), file.readPrefix(maxBytes = -1).assertSuccess())
    }

    @Test
    fun `readPrefix refuses a folder and a missing path as readBytes does`() {
        val missing = dir / "missing.txt"

        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readPrefix(maxBytes = 3))
        assertEquals(Err(FileError.NotFound(missing)), missing.readPrefix(maxBytes = 3))
    }

    @Test
    fun `readBytes hands accept the info of the file it opened and reads it when accepted`() {
        val file = (dir / "a.txt").writeRaw("abc")
        val seen = mutableListOf<FileInfo>()

        val read = file
            .readBytes(
                accept = { info ->
                    seen += info
                    true
                },
            ).assertSuccess()

        assertContentEquals("abc".encodeToByteArray(), read)
        assertEquals(file.info().assertSuccess(), seen.single())
    }

    @Test
    fun `readText refused by accept is Refused with the info accept was given`() {
        val file = (dir / "a.txt").writeRaw("abc")
        var given: FileInfo? = null

        val refused = file.readText(
            accept = { info ->
                given = info
                false
            },
        )

        assertEquals(Err(FileError.Refused(file, checkNotNull(given), RefusedBecause.Accept, file)), refused)
    }

    @Test
    fun `accept is not asked about a file refused before it is opened`() {
        val file = (dir / "a.txt").writeRaw("abcdef")
        var asked = false

        val asking = { _: FileInfo ->
            asked = true
            true
        }

        val tooLarge = file.readBytes(maxBytes = 3, accept = asking)
        val folder = dir.readText(accept = asking)

        assertEquals(Err(FileError.TooLarge(file, 3, 6)), tooLarge)
        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), folder)
        assertFalse(asked)
    }

    @Test
    fun `copyTo from a file this user may not read is Inaccessible for Denied and creates nothing`() {
        val file = (dir / "a.toml").writeRaw("old")
        val copy = dir / "copy.toml"
        val readable = { runCatching { file.readRaw() }.isSuccess }

        file.withMode(0, stillWorks = readable) {
            val failure = file.copyTo(copy).assertError<FileError.Inaccessible>()

            assertEquals(file, failure.path)
            assertEquals(IoCause.Denied, failure.cause)
        }
        assertFalse(SystemFileSystem.exists(copy))
    }
}

// kotlinx-io's JVM lookup asks exists() and then isFile(), so a file deleted in between reads this way.
private val tornLookup = FileMetadata(isRegularFile = false, isDirectory = false, size = -1)
