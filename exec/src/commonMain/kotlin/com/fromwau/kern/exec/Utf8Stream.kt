package com.fromwau.kern.exec

private const val REPLACEMENT = '�'
private const val MAX_UNFINISHED = 3
private val CONTINUATION = 0x80..0xBF

// The second byte after these leads is narrowed to rule out overlong forms, surrogates and code points past U+10FFFF.
private val AFTER_E0 = 0xA0..0xBF
private val AFTER_ED = 0x80..0x9F
private val AFTER_F0 = 0x90..0xBF
private val AFTER_F4 = 0x80..0x8F

/**
 * Decodes UTF-8 arriving in arbitrary chunks, holding back the start of a character until the rest comes.
 *
 * Each maximal subpart of an ill-formed sequence becomes one U+FFFD, as the Unicode standard and WHATWG recommend, so
 * the text is the same on every platform however the bytes are split.
 */
internal class Utf8Stream {
    private var codePoint = 0
    private var missing = 0
    private var nextAllowed = CONTINUATION

    /** Decodes the first [count] bytes of [bytes]. */
    fun feed(bytes: ByteArray, count: Int): String {
        val end = count.coerceIn(0, bytes.size)
        var start = 0
        var held: StringBuilder? = null
        if (missing > 0) {
            held = StringBuilder()
            while (start < end && missing > 0) decode(bytes[start++].toInt() and 0xFF, held)
        }
        // The platform decoders agree on well-formed input and are far faster than a loop per byte; only their
        // replacement of ill-formed input differs, so input that shows any U+FFFD is decoded here instead.
        val cut = end - unfinishedTail(bytes, start, end)
        val decoded = bytes.decodeToString(start, cut)
        if (decoded.indexOf(REPLACEMENT) < 0) {
            for (index in cut until end) decode(bytes[index].toInt() and 0xFF, null)
            return held?.append(decoded)?.toString() ?: decoded
        }
        val text = held ?: StringBuilder(end - start)
        for (index in start until end) decode(bytes[index].toInt() and 0xFF, text)
        return text.toString()
    }

    /** Flushes what is held: an unfinished character is U+FFFD. */
    fun end(): String {
        if (missing == 0) return ""
        missing = 0
        return REPLACEMENT.toString()
    }

    /** Takes one more byte, appending what it completes to [text]; null only for bytes that can complete nothing. */
    private fun decode(byte: Int, text: StringBuilder?) {
        if (missing > 0) {
            if (byte in nextAllowed) {
                codePoint = codePoint shl 6 or (byte and 0x3F)
                nextAllowed = CONTINUATION
                missing--
                if (missing == 0 && text != null) appendCodePoint(text)
                return
            }
            missing = 0
            text?.append(REPLACEMENT)
        }
        when (val length = sequenceLength(byte)) {
            0 -> text?.append(REPLACEMENT)
            1 -> text?.append(byte.toChar())
            else -> begin(byte, length)
        }
    }

    /** The length of the sequence that ends `bytes[start, end)` and can still become a character, else 0. */
    private fun unfinishedTail(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Int {
        for (back in 1..minOf(MAX_UNFINISHED, end - start)) {
            val byte = bytes[end - back].toInt() and 0xFF
            if (byte in CONTINUATION) continue
            if (sequenceLength(byte) <= back) return 0
            if (back == 1) return back
            val second = bytes[end - back + 1].toInt() and 0xFF
            return if (second in secondAfter(byte)) back else 0
        }
        return 0
    }

    private fun begin(
        lead: Int,
        length: Int,
    ) {
        // The lead carries the bits after its prefix of `length` ones and a zero.
        codePoint = lead and (0x7F shr length)
        missing = length - 1
        nextAllowed = secondAfter(lead)
    }

    private fun appendCodePoint(text: StringBuilder) {
        if (codePoint < 0x10000) {
            text.append(codePoint.toChar())
        } else {
            val above = codePoint - 0x10000
            text.append((0xD800 + (above shr 10)).toChar())
            text.append((0xDC00 + (above and 0x3FF)).toChar())
        }
    }

    /** How many bytes the sequence [byte] starts has, or 0 when no sequence starts with it. */
    private fun sequenceLength(byte: Int): Int = when (byte) {
        in 0x00..0x7F -> 1
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

    private fun secondAfter(lead: Int): IntRange = when (lead) {
        0xE0 -> AFTER_E0
        0xED -> AFTER_ED
        0xF0 -> AFTER_F0
        0xF4 -> AFTER_F4
        else -> CONTINUATION
    }
}
