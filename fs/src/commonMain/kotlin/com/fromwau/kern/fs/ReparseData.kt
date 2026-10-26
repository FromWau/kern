package com.fromwau.kern.fs

/**
 * The target that the first [length] bytes of a Windows symlink's or junction's reparse data name, read by the
 * offsets of the REPARSE_DATA_BUFFER layout: the print name, as stored, or the substitute name without its `\??\`
 * prefix where the print name is empty. Null for any other kind of reparse point, or data too short for its names.
 * Plain byte arithmetic, so it is tested on every target although only Windows native reads such data.
 */
internal fun linkTargetOf(
    data: ByteArray,
    length: Int,
): String? {
    if (length < REPARSE_HEADER_BYTES) return null
    val pathsStart = when (data.uIntAt(0)) {
        SYMLINK_TAG -> SYMLINK_PATHS_START
        MOUNT_POINT_TAG -> MOUNT_POINT_PATHS_START
        else -> return null
    }

    fun name(offsetAt: Int): String? {
        val start = pathsStart + data.uShortAt(offsetAt)
        val bytes = data.uShortAt(offsetAt + 2)
        if (start + bytes > length) return null
        return CharArray(bytes / 2) { data.uShortAt(start + it * 2).toChar() }.concatToString()
    }

    val printName = name(PRINT_NAME_OFFSET_AT) ?: return null
    if (printName.isNotEmpty()) return printName
    return name(SUBSTITUTE_NAME_OFFSET_AT)?.removePrefix(NT_PREFIX)
}

private fun ByteArray.uShortAt(index: Int): Int =
    (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.uIntAt(index: Int): UInt = (uShortAt(index) or (uShortAt(index + 2) shl 16)).toUInt()

/** IO_REPARSE_TAG_SYMLINK. */
private const val SYMLINK_TAG = 0xA000_000Cu

/** IO_REPARSE_TAG_MOUNT_POINT, a junction. */
private const val MOUNT_POINT_TAG = 0xA000_0003u

// The tag and two lengths, then each name's offset and length; a symlink then has four bytes of flags, a junction not.
private const val SUBSTITUTE_NAME_OFFSET_AT = 8
private const val PRINT_NAME_OFFSET_AT = 12
private const val MOUNT_POINT_PATHS_START = 16
private const val SYMLINK_PATHS_START = 20
private const val REPARSE_HEADER_BYTES = 16

private const val NT_PREFIX = "\\??\\"
