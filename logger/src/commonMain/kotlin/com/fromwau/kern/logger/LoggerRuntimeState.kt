package com.fromwau.kern.logger

import kotlinx.io.files.Path

/**
 * Everything the logger consults on its way to writing a line, held live so a change reaches the very next
 * entry.
 *
 * Hand a new one to [Logger.configure], or edit the current one with [Logger.update]. There is no
 * re-initialization step and nothing to restart: a settings screen that flips [level] to
 * [LogLevel.DEBUG] gets debug output immediately, and one that moves [file] gets the next line in the new
 * file.
 *
 * @param level the threshold. An entry below it is dropped before its message is ever built, so an
 *   expensive `{ }` block costs nothing while it is filtered out.
 * @param format how each line is rendered.
 * @param console whether to write to the platform console at all.
 * @param color whether that console line is wrapped in ANSI colour. Platforms that carry severity
 *   themselves ignore it, notably Android's logcat.
 * @param file the file to append to, already resolved by you. kern opens the path it is handed and never
 *   expands a `~` or picks a directory of its own. Null turns file logging off.
 */
public data class LoggerRuntimeState(
    val level: LogLevel = LogLevel.INFO,
    val format: LogFormat = LogFormat.TEXT,
    val console: Boolean = true,
    val color: Boolean = true,
    val file: Path? = null,
)
