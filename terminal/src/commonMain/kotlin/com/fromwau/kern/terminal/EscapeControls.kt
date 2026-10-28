package com.fromwau.kern.terminal

/**
 * This text with every character that does not print escaped as GNU's `ls --quoting-style=escape` escapes it, for
 * running text you print that holds input you do not control, such as an error message quoting a line of a file. The
 * text keeps its shape and gains no quotes; quote a whole name with [shellQuoted] instead.
 *
 * ```kotlin
 * "no key \"\u001B[31m\" at .a".escapeControls()    // no key "\033[31m" at .a
 * ```
 *
 * - A character that does not print is written with C's escape where it has one (`\n`, `\t`, `\r`), and otherwise as
 *   three octal digits per UTF-8 byte: an escape is `\033`, U+009B is `\302\233`. What does not print is what GNU
 *   decides: control characters, C1 controls, DEL, the line and paragraph separators and unassigned code points.
 * - A backslash is written `\\`, so an escaped character and the same text typed out never look alike.
 * - A space stays a space; GNU's escape style writes `\ `, which suits a name, not a sentence.
 *
 * @param keepLayout keeps a line break and a tab as they are. Use it only when every line break and tab in the text is
 *   yours, as in a message of several lines you built; escape each piece of input before you put it in such a message,
 *   or a line break in the input starts a line that reads like your own.
 */
public fun String.escapeControls(keepLayout: Boolean = false): String {
    val points = codePointList()
    if (points.none { it.isEscaped(keepLayout) }) return this
    return buildString {
        for (point in points) {
            when {
                point == '\\'.code -> append("\\\\")
                point.isEscaped(keepLayout) -> point.escapeInto(this)
                else -> appendPoint(point)
            }
        }
    }
}

private fun Int.isEscaped(keepLayout: Boolean): Boolean =
    this == '\\'.code || (!isPrintable() && !(keepLayout && (this == '\n'.code || this == '\t'.code)))
