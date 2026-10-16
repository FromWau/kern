package com.fromwau.kern.terminal

/**
 * A colour or attribute to apply to text. Combine them with `+`, and apply one with [render]:
 *
 * ```kotlin
 * (bold + red).render("failed", terminal.ansi)
 * ```
 *
 * The palette is [black], [red], [green], [yellow], [blue], [magenta], [cyan], [white] and [brightBlack], plus
 * the attributes [bold], [dim], [italic] and [underline]. Whether the escapes are actually emitted is not this type's
 * decision: pass [Terminal.ansi], or [ansiEnabled] if you resolve the environment yourself.
 */
public class Style internal constructor(internal val codes: List<Int>) {
    /** Combines two [Style]s into one that opens both codes and closes with a single reset. */
    public operator fun plus(other: Style): Style = Style(codes + other.codes)

    /**
     * Wraps [text] in this style's ANSI SGR codes when [enabled] and there is something to apply; returns
     * it unchanged otherwise, so a caller never branches on colour itself. A styled piece nested in [text] ends
     * with a reset, so this style is opened again after each one, and the text that follows keeps it.
     */
    public fun render(text: String, enabled: Boolean): String {
        if (!enabled || codes.isEmpty()) return text
        val open = "$ESC[${codes.joinToString(";")}m"
        return "$open${text.replace(RESET, RESET + open)}$RESET"
    }
}

/** Black text. */
public val black: Style = Style(listOf(30))

/** Red text. */
public val red: Style = Style(listOf(31))

/** Green text. */
public val green: Style = Style(listOf(32))

/** Yellow text. */
public val yellow: Style = Style(listOf(33))

/** Blue text. */
public val blue: Style = Style(listOf(34))

/** Magenta text. */
public val magenta: Style = Style(listOf(35))

/** Cyan text. */
public val cyan: Style = Style(listOf(36))

/** White text. */
public val white: Style = Style(listOf(37))

/** Bright black, a grey that stands off a dark background. */
public val brightBlack: Style = Style(listOf(90))

/** Bold text. */
public val bold: Style = Style(listOf(1))

/** Dimmed text. */
public val dim: Style = Style(listOf(2))

/** Italic text. */
public val italic: Style = Style(listOf(3))

/** Underlined text. */
public val underline: Style = Style(listOf(4))

private val ESC = Char(27).toString()
private val RESET = "$ESC[0m"
