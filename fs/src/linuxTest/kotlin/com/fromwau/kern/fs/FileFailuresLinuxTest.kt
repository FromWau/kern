package com.fromwau.kern.fs

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.io.IOException
import platform.posix.EACCES
import platform.posix.EDQUOT
import platform.posix.EEXIST
import platform.posix.EFBIG
import platform.posix.ELOOP
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.EPERM
import platform.posix.EROFS

@OptIn(InternalKernApi::class)
class FileFailuresLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `each errno kern types stands for its cause`() {
        assertEquals(IoCause.Denied, IoCause.ofErrno(EACCES))
        assertEquals(IoCause.Denied, IoCause.ofErrno(EPERM))
        assertEquals(IoCause.NotFound, IoCause.ofErrno(ENOENT))
        assertEquals(IoCause.ReadOnly, IoCause.ofErrno(EROFS))
        assertEquals(IoCause.NoSpace, IoCause.ofErrno(ENOSPC))
        assertEquals(IoCause.NoSpace, IoCause.ofErrno(EDQUOT))
        assertEquals(IoCause.Loop, IoCause.ofErrno(ELOOP))
    }

    @Test
    fun `any other errno is Other`() {
        assertEquals(IoCause.Other, IoCause.ofErrno(EFBIG))
        assertEquals(IoCause.Other, IoCause.ofErrno(EEXIST))
    }

    @Test
    fun `a message alone gives no cause`() {
        assertEquals(IoCause.Other, IOException("Permission denied").ioCause())
    }

    @Test
    fun `reading the info of a missing file throws why`() {
        val failure = assertFailsWith<OsCallFailure> { fileInfoAt(dir / "missing") }

        assertEquals(IoCause.NotFound, failure.ioCause)
    }

    @Test
    fun `resolving a missing path throws a failure of cause Other from kotlinx-io`() {
        val failure = assertFailsWith<IOException> { realPath(dir / "missing") }

        assertEquals(IoCause.Other, failure.ioCause())
    }

    @Test
    fun `reading the permissions of a file that cannot be looked up names that file`() {
        val missing = dir / "missing"

        val failure = assertFailsWith<OsCallFailure> { permissionBits(missing) }

        assertEquals(IoCause.NotFound, failure.ioCause)
        assertEquals("$missing: No such file or directory", failure.message)
    }

    @Test
    fun `setting the permissions of a missing file fails for NotFound naming that file`() {
        val missing = dir / "missing"

        val failure = assertFailsWith<OsCallFailure> { setFilePermissions(missing, OWNER_READ_WRITE) }

        assertEquals(IoCause.NotFound, failure.ioCause)
        assertEquals("$missing: No such file or directory", failure.message)
    }
}
