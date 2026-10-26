package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReparseDataTest {
    @Test
    fun `a relative symlink reads back its print name as stored`() {
        val data = reparseData(SYMLINK_TAG, substitute = "a.txt", print = "a.txt", flags = 1)

        assertEquals("a.txt", linkTargetOf(data, data.size))
    }

    @Test
    fun `an absolute symlink reads back its print name rather than the NT form`() {
        val data = reparseData(SYMLINK_TAG, substitute = "\\??\\C:\\data\\a.txt", print = "C:\\data\\a.txt", flags = 0)

        assertEquals("C:\\data\\a.txt", linkTargetOf(data, data.size))
    }

    @Test
    fun `a symlink without a print name reads its substitute name without the NT prefix`() {
        val data = reparseData(SYMLINK_TAG, substitute = "\\??\\C:\\data", print = "", flags = 0)

        assertEquals("C:\\data", linkTargetOf(data, data.size))
    }

    @Test
    fun `a junction reads back its print name from the shorter mount point layout`() {
        val data = reparseData(MOUNT_POINT_TAG, substitute = "\\??\\C:\\data", print = "C:\\data", flags = null)

        assertEquals("C:\\data", linkTargetOf(data, data.size))
    }

    @Test
    fun `another kind of reparse point names no target`() {
        val data = reparseData(0x8000_0013u, substitute = "x", print = "x", flags = 0)

        assertNull(linkTargetOf(data, data.size))
    }

    @Test
    fun `data shorter than the names it claims names no target`() {
        val data = reparseData(SYMLINK_TAG, substitute = "a.txt", print = "a.txt", flags = 0)

        assertNull(linkTargetOf(data, data.size - 2))
    }

    /** REPARSE_DATA_BUFFER bytes for [tag]; [flags] is written only for a symlink, whose layout has them. */
    private fun reparseData(
        tag: UInt,
        substitute: String,
        print: String,
        flags: Int?,
    ): ByteArray {
        val names = (substitute + print).flatMap { listOf(it.code.toByte(), (it.code shr 8).toByte()) }
        val header = mutableListOf<Byte>()
        fun short(value: Int) {
            header += value.toByte()
            header += (value shr 8).toByte()
        }
        short(tag.toInt())
        short((tag shr 16).toInt())
        short(0)
        short(0)
        short(0)
        short(substitute.length * 2)
        short(substitute.length * 2)
        short(print.length * 2)
        if (flags != null) {
            short(flags)
            short(0)
        }
        return (header + names).toByteArray()
    }

    private companion object {
        const val SYMLINK_TAG = 0xA000_000Cu
        const val MOUNT_POINT_TAG = 0xA000_0003u
    }
}
