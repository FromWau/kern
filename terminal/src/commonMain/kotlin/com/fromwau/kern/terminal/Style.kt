package com.fromwau.kern.terminal

/**
 * A colour or attribute to apply to text. Combine them with `+`, and invoke one on your text to get [Styled] text
 * that a [Terminal] colours only when its stream takes colour:
 *
 * ```kotlin
 * terminal.err((bold + red)("failed"))
 * ```
 *
 * The palette is [black], [red], [green], [yellow], [blue], [magenta], [cyan], [white] and [brightBlack], plus
 * the attributes [bold], [dim], [italic] and [underline], and [plain], which adds nothing. Two styles are equal when
 * they apply the same parts, whatever the order: `bold + red == red + bold`.
 */
public class Style internal constructor(parts: Collection<Sgr>) {
    internal val parts: List<Sgr> = parts.distinct().sortedBy { it.code }

    internal val open: String =
        if (this.parts.isEmpty()) "" else "$ESC[${this.parts.joinToString(";") { "${it.code}" }}m"

    /** A style that applies both this one and [other]. */
    public operator fun plus(other: Style): Style = Style(parts + other.parts)

    /**
     * [pieces] as one [Styled] text in this style. A piece may be a `String` or [Styled] text of its own, which keeps
     * its style and this one around it: `red("a ", bold("b"), " c")`.
     */
    public operator fun invoke(vararg pieces: CharSequence): Styled = Styled.of(this, pieces.asList())

    /** Wraps [text] in this style's escape codes when [enabled], and returns it unchanged otherwise. */
    @Deprecated(
        "Build Styled text instead, which a Terminal colours for its own stream. Its output differs: the reset goes " +
            "before every line break, and the style is not reopened after a reset inside the text.",
        ReplaceWith("this(text).render(enabled)"),
    )
    public fun render(text: String, enabled: Boolean): String {
        if (!enabled || parts.isEmpty()) return text
        return "$open${text.replace(RESET, RESET + open)}$RESET"
    }

    override fun equals(other: Any?): Boolean = other is Style && parts == other.parts

    override fun hashCode(): Int = parts.hashCode()

    /** The parts by name, such as `bold+red`, or `plain`. */
    override fun toString(): String = if (parts.isEmpty()) "plain" else parts.joinToString("+") { it.name }
}

// Declared before the palette, which reads them while this file initialises.
internal val ESC = Char(27).toString()
internal val RESET = "$ESC[0m"

/** One ANSI SGR code and the name it goes by in the palette. */
internal data class Sgr(val code: Int, val name: String)

private fun style(code: Int, name: String): Style = Style(listOf(Sgr(code, name)))

/** No colour or attribute: `plain("x")` writes `x`. */
public val plain: Style = Style(emptyList())

/** Black text. */
public val black: Style = style(30, "black")

/** Red text. */
public val red: Style = style(31, "red")

/** Green text. */
public val green: Style = style(32, "green")

/** Yellow text. */
public val yellow: Style = style(33, "yellow")

/** Blue text. */
public val blue: Style = style(34, "blue")

/** Magenta text. */
public val magenta: Style = style(35, "magenta")

/** Cyan text. */
public val cyan: Style = style(36, "cyan")

/** White text. */
public val white: Style = style(37, "white")

/** Bright black, a grey that stands off a dark background. */
public val brightBlack: Style = style(90, "brightBlack")

/** Bold text. */
public val bold: Style = style(1, "bold")

/** Dimmed text. */
public val dim: Style = style(2, "dim")

/** Italic text. */
public val italic: Style = style(3, "italic")

/** Underlined text. */
public val underline: Style = style(4, "underline")
