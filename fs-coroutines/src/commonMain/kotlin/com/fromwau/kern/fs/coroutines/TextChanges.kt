package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.readText
import com.fromwau.kern.result.Result
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * This file's text now, read as [readText] reads it, and again each time a read after a [changes] answer gives
 * something else, for as long as you collect.
 *
 * ```kotlin
 * config.textChanges(maxBytes = 1_048_576, trusted = true).collect { text ->
 *     text.onSuccess { load(it) }.onError { println("$config: $it") }
 * }
 * ```
 *
 * The file is read when collection starts and each time [changes] answers, and a read is an answer only when it
 * differs from the last one: other text, an error where there was text or the reverse, or another error. Errors are
 * compared as [FileError] compares, so two failures that differ only in their exception are one. So a `chmod` or a
 * write that leaves the text as it was gives no answer, and a deleted file is one [FileError.NotFound], then its text
 * once it is back. A [FileError.Refused] holds the [FileInfo] it found, so a file that changes while it is refused is
 * refused again. An error never ends the flow.
 *
 * @param maxBytes the largest file read; a larger one is [FileError.TooLarge].
 * @param trusted reads as [readText] with `trusted` does, refusing a file or folder someone besides you may change.
 * @param pollEvery how long to wait between lookups where there is no system watch, as [changes] takes it.
 * @return a cold flow: each collector watches and reads on its own.
 * @throws IllegalArgumentException when [pollEvery] is not positive.
 */
public fun Path.textChanges(
    maxBytes: Long,
    trusted: Boolean = false,
    pollEvery: Duration = 1.seconds,
): Flow<Result<String, FileError>> = changes(pollEvery).texts(this, maxBytes, trusted)

/**
 * [textChanges] looking the file up every [every] as [pollChanges] does, never asking the operating system to watch,
 * for a network file system or a FUSE mount whose changes the system watch misses.
 *
 * @param every how long to wait between lookups.
 * @throws IllegalArgumentException when [every] is not positive.
 */
public fun Path.pollTextChanges(
    maxBytes: Long,
    trusted: Boolean = false,
    every: Duration = 1.seconds,
): Flow<Result<String, FileError>> = pollChanges(every).texts(this, maxBytes, trusted)

/** This path's text, read on each of these answers, and only when it differs from the last read. */
private fun Flow<Result<FileInfo, FileError>>.texts(
    path: Path,
    maxBytes: Long,
    trusted: Boolean,
): Flow<Result<String, FileError>> = map { path.readText(maxBytes, trusted) }.distinctUntilChanged()
