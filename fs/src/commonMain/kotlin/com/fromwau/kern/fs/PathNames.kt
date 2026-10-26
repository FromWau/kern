package com.fromwau.kern.fs

import kotlinx.io.files.Path

/**
 * This path with [child] appended: `dir / "app.toml"`. [child] is taken as written, so a separator in it adds
 * more than one segment, `""` adds none, an absolute [child] such as `"/x"` is appended below this path rather than
 * replacing it, and `".."` is kept as a segment rather than resolved, so the result's `parent` is this path, not the
 * folder above it.
 */
public operator fun Path.div(child: String): Path = this / Path(child)

/**
 * This path with [child]'s names appended, by the same rule as `/` with a `String`: `dst / entry.relative`. An
 * absolute [child] is appended below this path rather than replacing it, and a `..` in it is kept as written.
 */
public operator fun Path.div(child: Path): Path {
    val names = if (child.isAbsolute) child.segments().drop(1) else child.segments()
    return if (names.isEmpty()) this else Path(this, *names.toTypedArray())
}

/**
 * Everything after the last dot of the final segment, so `archive.tar.gz` reports `gz`, as Kotlin's own
 * `File.extension` does. Unlike it, the result is null when there is no extension: no dot, a trailing dot, or only
 * a leading dot, which marks a hidden file, so `.bashrc` has none where `File.extension` says `bashrc`. Only the
 * name is inspected; the filesystem is never touched.
 */
public val Path.extension: String?
    get() = name
        .removePrefix(".")
        .substringAfterLast('.', missingDelimiterValue = "")
        .ifEmpty { null }

/** The final segment without its [extension]: `archive.tar.gz` gives `archive.tar`, and `.bashrc` stays `.bashrc`. */
public val Path.nameWithoutExtension: String
    get() = extension?.let { name.removeSuffix(".$it") } ?: name

/**
 * What keeps this string from being one name in a folder, such as a file name a user typed, or null when it can be
 * one. `/` and `\` are both [NameProblem.Separator] on every platform, so a name that passes is one name on Windows
 * too. A name longer than 255 bytes in UTF-8 is [NameProblem.TooLong], once every other check has passed. Last, a name
 * shaped like a copy a write leaves beside its file, such as `.notes.md.<id>.tmp`, is [NameProblem.LeftoverName]. Only
 * the string is inspected: nothing is looked up.
 *
 * ```kotlin
 * name.nameProblem()?.let { return println("not a file name: $it") }
 * val file = dir / name
 * ```
 *
 * @param staged also judges the name as the calls [MAX_STAGED_NAME_BYTES] lists take one: past it and up to 255 bytes
 *   it is [NameProblem.TooLongToStage], checked before [NameProblem.LeftoverName].
 */
public fun String.nameProblem(staged: Boolean = false): NameProblem? = plainNameProblem()
    ?: NameProblem.TooLongToStage.takeIf { staged && isTooLongToStage() }
    ?: NameProblem.LeftoverName.takeIf { isLeftoverName() }

/** Whether this name leaves no room for the link or claim a stage makes beside it, as [MAX_STAGED_NAME_BYTES] says. */
internal fun String.isTooLongToStage(): Boolean = encodeToByteArray().size > MAX_STAGED_NAME_BYTES

/** [nameProblem] without [NameProblem.LeftoverName], for a name that is looked at or removed rather than made. */
internal fun String.plainNameProblem(): NameProblem? = when {
    NUL in this -> NameProblem.Nul
    isBlank() -> NameProblem.Blank
    this == "." -> NameProblem.Dot
    this == ".." -> NameProblem.DotDot
    '/' in this || '\\' in this -> NameProblem.Separator
    encodeToByteArray().size > LONGEST_NAME_BYTES -> NameProblem.TooLong
    else -> null
}

/**
 * What keeps this string from being a relative path that stays below the folder it is joined to, such as a path a
 * manifest names inside a package, or null when it is one. Only the string is inspected: nothing is looked up, and a
 * symlink on the way is not seen. Every [Writer] call checks the names it is given this way.
 *
 * ```kotlin
 * settings.logFile.relativePathProblem()?.let { problem ->
 *     return println("logFile must be a path inside the log folder: $problem")
 * }
 * ```
 *
 * A leading `/` or `\`, or a drive such as `C:` at the start, is [RelativePathProblem.Absolute] on every platform. Any
 * other string is split at each `/`, and the first name [nameProblem] refuses is [RelativePathProblem.BadName], so
 * `a/../b` is `..` as [NameProblem.DotDot], `a//b` and `bin/` hold an empty name, [NameProblem.Blank], and a `\` inside
 * a name is [NameProblem.Separator]. The empty string is one empty name, `BadName(0, "", Blank)`, a name longer
 * than 255 bytes in UTF-8 is [NameProblem.TooLong], and a name shaped like a write's leftover is
 * [NameProblem.LeftoverName]: [Writer] calls that make an entry refuse it, while those that read or remove one take it.
 *
 * @param staged judges the last name as `nameProblem(staged = true)` does, so a path for one of the calls
 *   [MAX_STAGED_NAME_BYTES] lists is [NameProblem.TooLongToStage] when its last name is past it.
 */
public fun String.relativePathProblem(staged: Boolean = false): RelativePathProblem? {
    val lastNameIndex = count { it == '/' }
    return relativePathProblem { index, name -> name.nameProblem(staged = staged && index == lastNameIndex) }
}

/** [relativePathProblem], the name at each index judged by [problemOf]. */
internal fun String.relativePathProblem(problemOf: (index: Int, name: String) -> NameProblem?): RelativePathProblem? {
    if (startsWith('/') || startsWith('\\') || startsWithDrive()) return RelativePathProblem.Absolute
    split('/').forEachIndexed { index, name ->
        problemOf(index, name)?.let { return RelativePathProblem.BadName(index, name, it) }
    }
    return null
}

/** What [relativePathProblem] found in a string meant as a relative path. */
public sealed interface RelativePathProblem {
    /** The path starts at a root: a `/`, a `\`, or a drive such as `C:`. */
    public data object Absolute : RelativePathProblem

    /**
     * The name at [index], counted from zero between the `/`s, is [name], which is no name for the [problem] given.
     * Printed, a [name] over 64 characters is cut there and followed by its length in bytes.
     */
    public data class BadName(
        val index: Int,
        val name: String,
        val problem: NameProblem,
    ) : RelativePathProblem {
        override fun toString(): String = "BadName(index=$index, name=${name.cutToPrint()}, problem=$problem)"
    }
}

/**
 * This string as an error prints it: past [PRINTED_CHARS] characters, cut there and followed by `... (N bytes)`, N its
 * length in UTF-8.
 */
internal fun String.cutToPrint(): String {
    if (length <= PRINTED_CHARS) return this
    val kept = take(PRINTED_CHARS).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "$kept... (${encodeToByteArray().size} bytes)"
}

/** This path as an error prints it: each of its names [cutToPrint], between `/`s, or `/`s and `\`s on Windows. */
internal fun Path.namesCutToPrint(): String = toString().namesCutToPrint(if (windowsHost) "/\\" else "/")

/** This path as [Path.namesCutToPrint] prints it, its names lying between any of [separators]. */
internal fun String.namesCutToPrint(separators: String): String {
    val name = Regex(separators.map { "\\$it" }.joinToString("", prefix = "[^", postfix = "]+"))
    return name.replace(this) { it.value.cutToPrint() }
}

private const val PRINTED_CHARS = 64

/** Whether this starts with a drive, a letter and a colon, as `C:` does. */
private fun String.startsWithDrive(): Boolean = length >= 2 && this[1] == ':' && this[0].lowercaseChar() in 'a'..'z'

/**
 * This path with a leading `~` replaced by [home], when the `~` stands alone or is followed by `/` (or by `\` on
 * Windows). Any other path is returned unchanged, including `~bob/x`, which names another user's home, so the result is
 * relative whenever this path was and did not start with `~`.
 */
public fun Path.expandTilde(home: Path): Path {
    val raw = toString()
    val separatorFollows = raw.startsWith("~/") || (windowsHost && raw.startsWith("~\\"))
    return when {
        raw == "~" -> home
        separatorFollows -> Path(home.toString() + raw.substring(1))
        else -> this
    }
}

/**
 * This path written relative to [base], so `/a/b/c` relative to `/a` is `b/c`, and [base] itself gives `Path("")`. Only
 * names are compared, whole segment against whole segment, and the filesystem is never touched: a symlink is not
 * resolved, and `.` and `..` are compared as written. A rest holding `..`, such as `/a/../b` relative to `/a`, is not
 * under [base] by name, so it is null.
 *
 * @return the rest of this path below [base]; null when this path is not under [base], when the rest holds `..`,
 *   or when one of the two is absolute and the other is not.
 */
public fun Path.relativeTo(base: Path): Path? {
    if (isAbsolute != base.isAbsolute) return null
    val segments = segments()
    val baseSegments = base.segments()
    if (segments.size < baseSegments.size || segments.subList(0, baseSegments.size) != baseSegments) return null
    val rest = segments.drop(baseSegments.size)
    if (".." in rest) return null
    return if (rest.isEmpty()) Path("") else Path(rest.first(), *rest.drop(1).toTypedArray())
}

/**
 * The entry this path names, read by name alone: one ending in `.` names the folder that `.` is in, and one ending in
 * `..` the folder above that, so `a/b/..` names `a` whatever `b` is and whether or not it is there.
 */
internal fun Path.namedEntry(): Path {
    var level = this
    var ups = 0
    while (true) {
        when {
            level.name == "." -> Unit
            level.name == ".." -> ups++
            ups > 0 && level.name.isNotEmpty() -> ups--
            else -> return level
        }
        level = level.parent ?: return if (ups == 0) Path(".") else Path("..", *Array(ups - 1) { ".." })
    }
}

/**
 * This path read by its names alone: each `.` left out and each `..` taking away the name before it, a `..` at an
 * absolute path's root staying at the root. The filesystem is never touched, so a symlink is not resolved.
 */
internal fun Path.byNames(): Path {
    val segments = segments()
    val root = segments.firstOrNull()?.takeIf { isAbsolute }
    val kept = mutableListOf<String>()
    for (name in if (root == null) segments else segments.drop(1)) {
        when {
            name == "." -> Unit
            name != ".." -> kept += name
            kept.isNotEmpty() && kept.last() != ".." -> kept.removeAt(kept.lastIndex)
            root == null -> kept += name
        }
    }
    return when {
        root != null -> Path(root, *kept.toTypedArray())
        kept.isEmpty() -> Path(".")
        else -> Path(kept.first(), *kept.drop(1).toTypedArray())
    }
}

/** The path [name] beside this one, in the same folder. */
internal fun Path.sibling(name: String): Path = parent?.let { Path(it, name) } ?: Path(name)

/** The folder this path is in, `.` for a bare name. */
internal fun Path.folderOrDot(): Path = parent ?: Path(".")

/** This relative path as a [Writer] takes a name: its names joined by `/`. */
internal fun Path.writerName(): String = names().joinToString("/")

/** The names this path is made of, starting with its root when it has one; none for the empty path. */
internal fun Path.segments(): List<String> = generateSequence(this) { it.parent }
    // The root names itself only in full: `/`, or a drive such as `C:\`, has an empty name.
    .map { if (it.parent == null) it.toString() else it.name }
    .filter { it.isNotEmpty() }
    .toList()
    .asReversed()

// kotlinx-io reports `/` as mingw's SystemPathSeparator, so the separator cannot tell Windows apart. A drive
// path is absolute only on a Windows host, JVM or mingw.
private val windowsHost: Boolean = Path("C:\\x").isAbsolute
