package com.fromwau.kern.terminal

/**
 * A colour or attribute to apply to text. Combine them with `+`, and apply one with [render]:
 *
 * ```kotlin
 * (bold + red).render("failed", terminal.ansi)
 * ```
 *
 * The palette is [black], [red], [green], [yellow], [blue], [magenta], [cyan], [white], plus the attributes
 * [bold], [dim], [italic] and [underline]. Whether the escapes are actually emitted is not this type's
 * decision: pass [Terminal.ansi], or [ansiEnabled] if you resolve the environment yourself.
 */
public class Style internal constructor(internal val codes: List<Int>) {
    /** Combines two [Style]s into one that opens both codes and closes with a single reset. */
    public operator fun plus(other: Style): Style = Style(codes + other.codes)

    /**
     * Wraps [text] in this style's ANSI SGR codes when [enabled] and there is something to apply; returns
     * it unchanged otherwise, so a caller never branches on colour itself.
     */
    public fun render(text: String, enabled: Boolean): String =
        if (enabled && codes.isNotEmpty()) "$ESC[${codes.joinToString(";")}m$text$ESC[0m" else text
}

public val black: Style = Style(listOf(30))
public val red: Style = Style(listOf(31))
public val green: Style = Style(listOf(32))
public val yellow: Style = Style(listOf(33))
public val blue: Style = Style(listOf(34))
public val magenta: Style = Style(listOf(35))
public val cyan: Style = Style(listOf(36))
public val white: Style = Style(listOf(37))

/** Bright variants, for a foreground that needs to stand off a dark background. */
public val brightBlack: Style = Style(listOf(90))

public val bold: Style = Style(listOf(1))
public val dim: Style = Style(listOf(2))
public val italic: Style = Style(listOf(3))
public val underline: Style = Style(listOf(4))

private val ESC = Char(27).toString()
