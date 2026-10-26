package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlinx.io.IOException
import kotlinx.io.files.Path

class FileErrorTest {
    private val path = Path("/a/b.toml")
    private val elsewhere = Path("/a/.b.toml.tmp")
    private val one = IOException("one")
    private val another = IOException("another")

    @Test
    fun `Inaccessible compares its path and cause and not its exception`() {
        assertEqualIgnoringException(
            FileError.Inaccessible(path, IoCause.Denied, one),
            FileError.Inaccessible(path, IoCause.Denied, another),
        )
        assertNotEquals(
            FileError.Inaccessible(path, IoCause.Denied, one),
            FileError.Inaccessible(path, IoCause.Other, one),
        )
    }

    @Test
    fun `WriteFailed compares its path and cause and not its exception`() {
        assertEqualIgnoringException(
            FileError.WriteFailed(path, IoCause.Denied, one),
            FileError.WriteFailed(path, IoCause.Denied, null),
        )
        assertNotEquals(
            FileError.WriteFailed(path, IoCause.Denied, one),
            FileError.WriteFailed(path, IoCause.NoSpace, one),
        )
        assertEqualIgnoringException(
            FileError.WriteFailed(path, IoCause.Denied, one, at = FailedAt.Temp(elsewhere)),
            FileError.WriteFailed(path, IoCause.Denied, another, at = FailedAt.Temp(elsewhere)),
        )
        assertNotEquals(
            FileError.WriteFailed(path, IoCause.Denied, one),
            FileError.WriteFailed(path, IoCause.Denied, one, at = FailedAt.Temp(elsewhere)),
        )
        assertNotEquals(
            FileError.WriteFailed(path, IoCause.Denied, one, at = FailedAt.Temp(elsewhere)),
            FileError.WriteFailed(path, IoCause.Denied, one, at = FailedAt.Backup(elsewhere)),
        )
    }

    @Test
    fun `LockFailed compares its path and cause and not its exception`() {
        assertEqualIgnoringException(
            FileError.LockFailed(path, IoCause.Denied, one),
            FileError.LockFailed(path, IoCause.Denied, another),
        )
        assertNotEquals(
            FileError.LockFailed(path, IoCause.Denied, one),
            FileError.LockFailed(path, IoCause.Other, one),
        )
    }

    @Test
    fun `RestoreFailed compares its backup too and not its exception`() {
        val backup = Path("/a/.b.toml.bak")
        assertEqualIgnoringException(
            FileError.RestoreFailed(path, IoCause.Denied, one, backup),
            FileError.RestoreFailed(path, IoCause.Denied, another, backup),
        )
        assertNotEquals(
            FileError.RestoreFailed(path, IoCause.Denied, one, backup),
            FileError.RestoreFailed(path, IoCause.Other, one, backup),
        )
        assertNotEquals(
            FileError.RestoreFailed(path, IoCause.Denied, one, backup),
            FileError.RestoreFailed(path, IoCause.Denied, one, backup = null),
        )
        assertNotEquals(
            FileError.RestoreFailed(path, IoCause.Denied, one, backup),
            FileError.RestoreFailed(path, IoCause.Denied, one, backup, at = FailedAt.Temp(elsewhere)),
        )
    }

    @Test
    fun `an error still shows its exception when printed`() {
        assertEquals(
            "WriteFailed(path=$path, cause=Denied, exception=$one, at=null)",
            FileError.WriteFailed(path, IoCause.Denied, one).toString(),
        )
    }

    @Test
    fun `an InvalidName prints a NUL in its path as backslash zero and keeps the path itself`() {
        val nul = Path("/a/b${Char(0)}c.toml")
        val error = FileError.InvalidName(nul, NameProblem.Nul)

        assertEquals("InvalidName(path=/a/b\\0c.toml, reason=Nul)", error.toString())
        assertEquals("/a/b${Char(0)}c.toml", error.path.toString())
    }

    @Test
    fun `a Refused prints its path and why and the permissions it saw`() {
        val mode = Permissions(0b111_111_111)
        val info = FileInfo(FileType.Directory, 60, Instant.fromEpochSeconds(0), null, mode, true, 1)

        assertEquals(
            "Refused($path: OthersMayWrite, rwxrwxrwx)",
            FileError.Refused(path, info, RefusedBecause.OthersMayWrite, path).toString(),
        )
    }

    @Test
    fun `a Refused with no permissions to show prints its path and why`() {
        val info = FileInfo(FileType.Regular, 1, Instant.fromEpochSeconds(0), null, null, false, 1)

        assertEquals(
            "Refused($path: OtherOwner)",
            FileError.Refused(path, info, RefusedBecause.OtherOwner, path).toString(),
        )
    }

    @Test
    fun `a Refused for a place other than the path asked prints both`() {
        val mode = Permissions(0b111_111_101)
        val info = FileInfo(FileType.Directory, 60, Instant.fromEpochSeconds(0), null, mode, true, 1)
        val asked = path / "app.toml"

        assertEquals(
            "Refused($path for $asked: OthersMayWrite, rwxrwxr-x)",
            FileError.Refused(path, info, RefusedBecause.OthersMayWrite, asked).toString(),
        )
    }

    @Test
    fun `a BadName prints a name over 64 characters cut with its length in bytes and keeps the name itself`() {
        val long = "a".repeat(300)
        val problem = RelativePathProblem.BadName(0, long, NameProblem.TooLong)

        assertEquals("BadName(index=0, name=${"a".repeat(64)}... (300 bytes), problem=TooLong)", problem.toString())
        assertEquals(long, problem.name)
    }

    @Test
    fun `a BadName prints a name of 64 characters whole`() {
        val name = "\\".repeat(64)

        assertEquals(
            "BadName(index=1, name=$name, problem=Separator)",
            RelativePathProblem.BadName(1, name, NameProblem.Separator).toString(),
        )
    }

    @Test
    fun `an InvalidRelativePath prints an entry over 64 characters cut with its length in bytes and keeps the entry`() {
        val long = "é".repeat(150)
        val folder = Path("/a")
        val error = FileError.InvalidRelativePath(folder, long, assertNotNull(long.relativePathProblem()))

        val cut = "${"é".repeat(64)}... (300 bytes)"
        assertEquals(
            "InvalidRelativePath(folder=$folder, entry=$cut, problem=BadName(index=0, name=$cut, problem=TooLong))",
            error.toString(),
        )
        assertEquals(long, error.entry)
    }

    @Test
    fun `a path printed for Windows cuts each name between slashes and backslashes and keeps both`() {
        val long = "a".repeat(100)
        val cut = "${"a".repeat(64)}... (100 bytes)"

        assertEquals("C:\\$cut/$cut\\b.txt", "C:\\$long/$long\\b.txt".namesCutToPrint(separators = "/\\"))
    }

    @Test
    fun `a path printed for a POSIX host keeps a backslash inside the name it belongs to`() {
        val long = "a".repeat(100)

        val cut = "${"a".repeat(64)}... (201 bytes)"
        assertEquals("/x/$cut", "/x/$long\\$long".namesCutToPrint(separators = "/"))
    }

    @Test
    fun `a NameTooLong prints each name over 64 characters in its path cut with its length in bytes`() {
        val long = "a".repeat(230)
        val error = FileError.NameTooLong(Path("/a", long, "b.txt"), LongName.Path, 230)

        val cut = "${"a".repeat(64)}... (230 bytes)"
        assertEquals("NameTooLong(path=${Path("/a", cut, "b.txt")}, name=Path, bytes=230)", error.toString())
        assertEquals(long, error.path.parent?.name)
    }

    @Test
    fun `a failed OS call prints as its path and words alone`() {
        assertEquals("$path: Permission denied", OsCallFailure(IoCause.Denied, path, "Permission denied").toString())
    }

    @Test
    fun `an errno value stands for the cause its name does and one with no typed name is Other`() {
        val values = mapOf(Errno.EACCES to 13, Errno.ELOOP to 40)

        assertEquals(IoCause.Denied, causeOfErrno(13) { values[it] })
        assertEquals(IoCause.Loop, causeOfErrno(40) { values[it] })
        assertEquals(IoCause.Other, causeOfErrno(2) { values[it] })
    }

    private fun assertEqualIgnoringException(
        first: FileError,
        second: FileError,
    ) {
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
