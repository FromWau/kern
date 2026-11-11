@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.linux.SYS_getxattr
import platform.linux.SYS_listxattr
import platform.linux.SYS_setxattr
import platform.posix.ENOTSUP
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.errno
import platform.posix.syscall

private const val USER_ATTRIBUTE = "user.kern"
private const val ACL_ATTRIBUTE = "system.posix_acl_access"
private const val DEFAULT_ACL_ATTRIBUTE = "system.posix_acl_default"

// version 2, then tag, permissions and id: the owner rw, the user 65534 rw, the group nothing, a mask of rw, others r.
private val ACL_WITH_A_NAMED_USER = byteArrayOf(
    2, 0, 0, 0,
    1, 0, 6, 0, -1, -1, -1, -1,
    2, 0, 6, 0, -2, -1, 0, 0,
    4, 0, 0, 0, -1, -1, -1, -1,
    16, 0, 6, 0, -1, -1, -1, -1,
    32, 0, 4, 0, -1, -1, -1, -1,
)

class AttributesLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = dir.deleteTree()

    /** Runs [block] on a file carrying [name], or passes with a note where this file system takes none. */
    private fun withAttribute(
        name: String = USER_ATTRIBUTE,
        value: ByteArray = byteArrayOf(1, 2, 3),
        block: (Path) -> Unit,
    ) {
        val file = (dir / "a.toml").writeRaw("old")
        if (file.setAttribute(name, value)) block(file) else println("skipped: $dir takes no $name")
    }

    @Test
    fun `an Auto write of a file with a user attribute renames and keeps it`() = withAttribute { file ->
        val before = file.inode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())

        assertEquals("new", file.readRaw())
        assertNotEquals(before, file.inode())
        assertEquals(listOf(USER_ATTRIBUTE), file.attributeNames())
        assertContentEquals(byteArrayOf(1, 2, 3), file.attributeValue(USER_ATTRIBUTE))
    }

    @Test
    fun `an Auto write of a file with a named ACL entry renames and keeps the ACL and the mode`() =
        withAttribute(ACL_ATTRIBUTE, ACL_WITH_A_NAMED_USER) { file ->
            val mode = file.mode()

            assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())

            assertContentEquals(ACL_WITH_A_NAMED_USER, file.attributeValue(ACL_ATTRIBUTE))
            assertEquals(mode, file.mode())
        }

    @Test
    fun `an Atomic write of a file with an attribute renames and keeps it`() = withAttribute { file ->
        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Atomic).way())

        assertEquals(listOf(USER_ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `a Replace write of a file with an attribute renames and keeps it`() = withAttribute { file ->
        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Replace).way())

        assertEquals(listOf(USER_ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `an Auto write through a link keeps the attributes of the file the link reaches`() = withAttribute { file ->
        val link = (dir / "link.toml").rawSymlinkTo(file)

        assertEquals(Ok(WriteWay.Renamed), link.writeText("new").way())

        assertTrue(link.isLink())
        assertEquals(listOf(USER_ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `an Auto write whose attributes cannot be copied writes in place and keeps them`() = withAttribute { file ->
        val before = file.inode()

        val written = file.write(WriteMode.Auto, carryAttributes = { _, _ -> false }) { it.writeString("new") }

        assertEquals(Ok(WriteWay.InPlace), written.way())
        assertEquals(before, file.inode())
        assertEquals(listOf(USER_ATTRIBUTE), file.attributeNames())
        assertEquals(emptyList(), SystemFileSystem.list(dir).filter { it.name.startsWith(".") })
    }

    @Test
    fun `an Atomic write whose attributes cannot be copied is refused and leaves the file`() = withAttribute { file ->
        val refused = file.write(WriteMode.Atomic, carryAttributes = { _, _ -> false }) { it.writeString("new") }

        assertEquals(Err(FileError.AtomicRefused(file, AtomicRefusal.Attributes)), refused)
        assertEquals("old", file.readRaw())
        assertEquals(listOf(file), SystemFileSystem.list(dir))
    }

    @Test
    fun `a Replace write whose attributes cannot be copied renames anyway`() = withAttribute { file ->
        val written = file.write(WriteMode.Replace, carryAttributes = { _, _ -> false }) { it.writeString("new") }

        assertEquals(Ok(WriteWay.Renamed), written.way())
        assertEquals("new", file.readRaw())
    }

    @Test
    fun `a file without an ACL gets none from its folder's default ACL`() {
        val file = (dir / "a.toml").writeRaw("old")
        if (!dir.setAttribute(DEFAULT_ACL_ATTRIBUTE, ACL_WITH_A_NAMED_USER)) {
            return println("skipped: $dir takes no $DEFAULT_ACL_ATTRIBUTE")
        }
        val mode = file.mode()

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())

        assertEquals(emptyList(), file.attributeNames())
        assertEquals(mode, file.mode())
    }

    @Test
    fun `the backup of a file with an ACL is for its owner alone`() =
        withAttribute(ACL_ATTRIBUTE, ACL_WITH_A_NAMED_USER) { file ->
            var backupMode: Int? = null

            file.write(WriteMode.InPlace) { sink ->
                val backup = SystemFileSystem.list(dir).single { it.name.endsWith(".bak") }
                backupMode = backup.mode()
                sink.writeString("new")
            }.assertSuccess()

            assertEquals(S_IRUSR or S_IWUSR, backupMode)
            assertEquals("new", file.readRaw())
        }

    @Test
    fun `an Auto write of a file with no attributes renames`() {
        val file = (dir / "a.toml").writeRaw("old")

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())
    }

    @Test
    fun `a security label alone is not counted as an attribute the file carries`() {
        assertFalse(listOf("security.selinux").holdsMoreThanSecurityLabels())
    }

    @Test
    fun `any other attribute beside a security label is counted`() {
        assertTrue(listOf("security.selinux", "user.kern").holdsMoreThanSecurityLabels())
    }
}

/** Whether [name] was set; false only where this file system takes none, any other failure failing the test. */
private fun Path.setAttribute(
    name: String,
    value: ByteArray,
): Boolean {
    val path = toString()
    return memScoped {
        val answer = value.usePinned { pinned ->
            syscall(
                SYS_setxattr.toLong(),
                path.cstr.ptr,
                name.cstr.ptr,
                pinned.addressOf(0),
                value.size.toLong(),
                0L,
            )
        }
        if (answer != 0L) check(errno == ENOTSUP) { "cannot set $name on $path: errno $errno" }
        answer == 0L
    }
}

private fun Path.attributeNames(): List<String> {
    val path = toString()
    return memScoped {
        val names = allocArray<ByteVar>(1024)
        val size = syscall(SYS_listxattr.toLong(), path.cstr.ptr, names, 1024L)
        check(size >= 0) { "cannot list the attributes of $path" }
        names
            .readBytes(size.toInt())
            .decodeToString()
            .split('\u0000')
            .filter { it.isNotEmpty() && !it.startsWith("security.") }
    }
}

private fun Path.attributeValue(name: String): ByteArray {
    val path = toString()
    return memScoped {
        val value = allocArray<ByteVar>(1024)
        val size = syscall(SYS_getxattr.toLong(), path.cstr.ptr, name.cstr.ptr, value, 1024L)
        check(size >= 0) { "cannot read $name of $path" }
        value.readBytes(size.toInt())
    }
}
