package com.fromwau.kern.dirs

import kotlinx.io.files.Path

/** This path with [child] appended as one more segment: `dir / "app.toml"`. */
public operator fun Path.div(child: String): Path = Path(this, child)

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
 * This path with a leading `~` replaced by [home], when the `~` stands alone or is followed by `/` (or by
 * `\` on Windows). Any other path is returned unchanged, including `~bob/x`, which names another user's home.
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

// kotlinx-io reports `/` as mingw's SystemPathSeparator, so the separator cannot tell Windows apart. A drive
// path is absolute only on a Windows host, JVM or mingw.
private val windowsHost: Boolean = Path("C:\\x").isAbsolute
