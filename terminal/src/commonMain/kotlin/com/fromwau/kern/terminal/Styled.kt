package com.fromwau.kern.terminal

/**
 * Text in a [Style], which turns into escape codes only when a [Terminal] writes it, and only when that stream takes
 * colour. Build it by invoking a style, nest styles and plain strings in one call, and join pieces with `+`:
 *
 * ```kotlin
 * val line = red("error: ", bold(path), " is missing") + "\n"
 * terminal.err(line)
 * ```
 *
 * Text after a nested piece is written in the enclosing style again. Each line is closed on its own: the reset goes
 * before every line break and the next line opens its styles again, so `app | head -1` leaves no colour behind. Line
 * breaks alone are written without codes, and an empty piece writes nothing.
 *
 * As a `CharSequence` it is the text without the codes its styles add: [length], [toString] and a string template
 * never contain them. An escape code you put inside a `String` piece yourself is written unchanged, which is the one
 * way to write codes this type does not know, and [length] and padding count its characters like any other text.
 *
 * Two styled texts are equal when they are built the same way: `red(red("a"))` is not `red("a")`, and
 * `red("a") + red("b")` is not `red("ab")`.
 */
public class Styled private constructor(
    private val style: Style,
    private val parts: List<CharSequence>,
) : CharSequence {
    private val text: String = parts.joinToString("")

    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    /** The plain text from [startIndex] to [endIndex], without its styles. */
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    /**
     * This text followed by [other], each in its own style. A `String` on the left of `+` stays a `String` and drops
     * the styles, so start with the styled piece, or write `plain("text ", red("x"))`.
     */
    public operator fun plus(other: CharSequence): Styled = of(plain, listOf(this, other))

    /** This text padded with unstyled [padChar] at the end to [length] characters, not counting style codes. */
    public fun padEnd(length: Int, padChar: Char = ' '): Styled = this + padding(length, padChar)

    /** This text padded with unstyled [padChar] at the start to [length] characters, not counting style codes. */
    public fun padStart(length: Int, padChar: Char = ' '): Styled = of(plain, listOf(padding(length, padChar), this))

    private fun padding(length: Int, padChar: Char): String =
        padChar.toString().repeat((length - this.length).coerceAtLeast(0))

    /**
     * The text with its escape codes when [ansi], and the plain text otherwise. [Terminal.out] and [Terminal.err]
     * call this with their stream's decision; call it yourself only to hand the text to something that is not a
     * [Terminal].
     */
    public fun render(ansi: Boolean): String {
        if (!ansi) return text
        var inEffect = ""
        return buildString {
            forEachRun(enclosing = "") { codes, run ->
                val needed = if (run[0] == '\r' || run[0] == '\n') "" else codes
                // A nested style only adds codes to the ones in effect; dropping any takes a reset.
                if (!needed.startsWith(inEffect)) {
                    append(RESET)
                    inEffect = ""
                }
                append(needed, inEffect.length, needed.length).append(run)
                inEffect = needed
            }
            if (inEffect.isNotEmpty()) append(RESET)
        }
    }

    /** Calls [action] for each run of line breaks and each run of other text, with the codes in effect for it. */
    private fun forEachRun(enclosing: String, action: (codes: String, run: String) -> Unit) {
        val codes = enclosing + style.open
        parts.forEach { part ->
            if (part is Styled) {
                part.forEachRun(codes, action)
            } else {
                LINE_BREAKS_OR_TEXT.findAll(part).forEach { action(codes, it.value) }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is Styled && style == other.style && parts == other.parts

    override fun hashCode(): Int = 31 * style.hashCode() + parts.hashCode()

    /** The plain text, without escape codes. */
    override fun toString(): String = text

    internal companion object {
        private val LINE_BREAKS_OR_TEXT = Regex("[\r\n]+|[^\r\n]+")

        /**
         * [style] around [pieces], kept in one shape per text and styles: plain pieces are spliced into their
         * parent, adjacent strings joined, and empty pieces dropped.
         */
        fun of(style: Style, pieces: List<CharSequence>): Styled {
            val parts = mutableListOf<CharSequence>()
            pieces.forEach { parts.addPiece(it) }
            val only = parts.singleOrNull()
            return if (style == plain && only is Styled) only else Styled(style, parts)
        }

        private fun MutableList<CharSequence>.addPiece(piece: CharSequence) {
            when {
                piece.isEmpty() -> Unit
                piece is Styled && piece.style == plain -> piece.parts.forEach { addPiece(it) }
                piece is Styled -> add(piece)
                lastOrNull() is String -> add(removeAt(lastIndex).toString() + piece)
                else -> add(piece.toString())
            }
        }
    }
}

/** Writes [text] to standard output, coloured when [Terminal.ansi] is true. */
public fun Terminal.out(text: Styled): Unit = out(text.render(ansi))

/** Writes [text] to standard error, coloured when [Terminal.errAnsi] is true. */
public fun Terminal.err(text: Styled): Unit = err(text.render(errAnsi))

/**
 * Joins the pieces like `joinToString`, keeping each piece's style and the styles of [separator], [prefix] and
 * [postfix]. `joinToString` gives the plain text instead, the `toString` of each piece.
 *
 * ```kotlin
 * terminal.out(listOf(red("a"), bold("b"), "c").joinToStyled(separator = dim(" | ")) + "\n")
 * ```
 */
public fun Iterable<CharSequence>.joinToStyled(
    separator: CharSequence = ", ",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
): Styled {
    val pieces = mutableListOf(prefix)
    forEachIndexed { index, piece ->
        if (index > 0) pieces += separator
        pieces += piece
    }
    pieces += postfix
    return Styled.of(plain, pieces)
}
