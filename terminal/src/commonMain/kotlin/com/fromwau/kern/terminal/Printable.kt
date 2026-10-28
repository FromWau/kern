package com.fromwau.kern.terminal

/**
 * Whether this code point prints, as glibc's `iswprint` answers in a UTF-8 locale and so as GNU coreutils decides what
 * to escape: C0 and C1 controls, DEL, the line and paragraph separators, surrogates, noncharacters and unassigned
 * code points do not. Unassigned is read from the platform's Unicode data for the Basic Multilingual Plane, which can
 * lag or lead glibc's by a Unicode version; past it only noncharacters count.
 */
internal fun Int.isPrintable(): Boolean = when {
    this < 0x20 || this == 0x7F -> false
    this in 0x80..0x9F -> false
    this == 0x2028 || this == 0x2029 -> false
    this in 0xD800..0xDFFF -> false
    this in 0xFDD0..0xFDEF || (this and 0xFFFE) == 0xFFFE -> false
    this < 0x10000 -> toChar().category != CharCategory.UNASSIGNED
    else -> true
}

/** Writes this code point as GNU does: a C escape such as `\n` where C has one, else each UTF-8 byte in octal. */
internal fun Int.escapeInto(out: StringBuilder) {
    val letter = when (this) {
        0x07 -> 'a'
        0x08 -> 'b'
        0x09 -> 't'
        0x0A -> 'n'
        0x0B -> 'v'
        0x0C -> 'f'
        0x0D -> 'r'
        else -> null
    }
    if (letter != null) {
        out.append('\\').append(letter)
        return
    }
    // A lone surrogate has no UTF-8 of its own; it is written as the replacement character an encoder gives it.
    val bytes = if (this in 0xD800..0xDFFF) byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()) else utf8()
    for (byte in bytes) out.append('\\').append((byte.toInt() and 0xFF).toString(8).padStart(3, '0'))
}

private fun Int.utf8(): ByteArray = buildString { appendPoint(this@utf8) }.encodeToByteArray()

/** This text's code points; a lone surrogate stands for itself. */
internal fun String.codePointList(): List<Int> {
    val points = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val high = this[index]
        val low = getOrNull(index + 1)
        if (high.isHighSurrogate() && low != null && low.isLowSurrogate()) {
            points += 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            index += 2
        } else {
            points += high.code
            index += 1
        }
    }
    return points
}

internal fun StringBuilder.appendPoint(point: Int) {
    if (point < 0x10000) {
        append(point.toChar())
        return
    }
    val offset = point - 0x10000
    append((0xD800 + (offset shr 10)).toChar())
    append((0xDC00 + (offset and 0x3FF)).toChar())
}
