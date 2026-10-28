package com.fromwau.kern.terminal

/**
 * This text quoted as GNU coreutils quotes a file name, so a name you do not control prints safely. [Terminal.out],
 * [Terminal.err] and [Styled] write text as it is, so quote such a name before printing it. Pasted into bash, the
 * result is the same name, except where GNU leaves a brace pattern such as `a{b,c}` unquoted and bash expands it; quote
 * with `always = true` to paste any name back exactly.
 *
 * ```kotlin
 * "notes.toml".shellQuoted()             // notes.toml
 * "my notes.toml".shellQuoted()          // 'my notes.toml'
 * "it's.toml".shellQuoted()              // "it's.toml"
 * "evil\u001B[31m.toml".shellQuoted()    // 'evil'$'\033''[31m.toml'
 * ```
 *
 * It matches `ls --quoting-style=shell-escape` and the names in GNU's messages, as `cat` prints them:
 * - A name of letters, digits, non-ASCII text that prints and `%+,-./:@]_` prints as it is, and so do `#` and `~`
 *   past its start and `{` or `}` beside other characters.
 * - Any other name is put in single quotes, a single quote inside written `'\''`. A name with a single quote whose
 *   other special characters are only spaces, `:` or a leading `#` or `~` is put in double quotes instead
 *   (`"it's a.toml"`).
 * - A character that does not print, such as a line break, an escape, DEL, a C1 control or an unassigned code point,
 *   is written in `$'…'` with C's escapes (`\n`, `\t`) or three octal digits per byte (`\033`).
 *
 * @param forMessage quotes a name holding `:` too, as GNU does where a name stands in a `name: message` line, so the
 *   colon cannot be read as the end of the name.
 * @param always quotes every name, a plain one too (`'notes.toml'`), as `ls --quoting-style=shell-escape-always`
 *   does, for a word you set apart in a sentence, as GNU's option errors do (`unrecognized option '--nope'`).
 */
public fun String.shellQuoted(
    forMessage: Boolean = false,
    always: Boolean = false,
): String {
    if (isEmpty()) return "''"
    val points = codePointList()
    if (!always && points.indices.none { needsQuoting(points, it, forMessage) }) return this
    if ('\'' in this && points.indices.all { fitsDoubleQuotes(points, it) }) return "\"$this\""
    return buildString {
        append('\'')
        var escaping = false
        for (point in points) {
            when {
                point == '\''.code -> {
                    append("'\\''")
                    escaping = false
                }
                !point.isPrintable() -> {
                    if (!escaping) append("'$'")
                    point.escapeInto(this)
                    escaping = true
                }
                else -> {
                    if (escaping) append("''")
                    appendPoint(point)
                    escaping = false
                }
            }
        }
        append('\'')
    }
}

private fun needsQuoting(
    points: List<Int>,
    index: Int,
    forMessage: Boolean,
): Boolean {
    val point = points[index]
    return when {
        !point.isPrintable() -> true
        point >= 0x80 -> false
        point.toChar() in SAFE -> false
        point.toChar() == ':' -> forMessage
        point.toChar() in "#~" -> index == 0
        point.toChar() in "{}" -> points.size == 1
        else -> true
    }
}

/** Whether GNU keeps the double-quote form for this character, which a shell and C read alike. */
private fun fitsDoubleQuotes(
    points: List<Int>,
    index: Int,
): Boolean {
    val point = points[index]
    return when {
        !point.isPrintable() -> false
        point >= 0x80 -> true
        point.toChar() in SAFE || point.toChar() in ": '" -> true
        point.toChar() in "#~" -> index == 0
        else -> false
    }
}

private const val SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789%+,-./@]_"
