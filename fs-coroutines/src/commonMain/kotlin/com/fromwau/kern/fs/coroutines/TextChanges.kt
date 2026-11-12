package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.readText
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path

/**
 * This file's text now, read as [readText] reads it, and again each time a later read gives something else, for as
 * long as you collect.
 *
 * ```kotlin
 * config.textChanges(maxBytes = 1_048_576, trusted = true, watch = Watch.System()).collect { text ->
 *     text.onSuccess { load(it) }.onError { println("$config: $it") }
 * }
 * Path("/proc/meminfo").textChanges(maxBytes = 65_536, watch = TextWatch.Read(2.seconds)).collect { text ->
 *     text.onSuccess { show(parseMemInfo(it)) }
 * }
 * ```
 *
 * The file is read when collection starts, then each time the file may have changed under a [Watch], or every
 * [TextWatch.Read.every] under [TextWatch.Read]. A read is an answer only when it differs from the last one: other
 * text, an error where there was text or the reverse, or another error. Errors are compared as [FileError] compares,
 * so two failures that differ only in their exception are one. So a `chmod` or a write that leaves the text as it was
 * gives no answer, and a deleted file is one [FileError.NotFound], then its text once it is back. A
 * [FileError.Refused] holds the [FileInfo] it found, so a file that changes while it is refused is refused again. An
 * error never ends the flow, but for [FileError.Unwatchable]: under a [Watch], a file on procfs or sysfs answers that
 * once, unread, and the flow ends, since only [TextWatch.Read] can follow such a file.
 *
 * Under [TextWatch.Read] the next read comes [TextWatch.Read.every] after a read ends and you have handled its answer,
 * if it gave one, so a slow read or a slow collector stretches the time between two. [TextWatch.Read.every] is not
 * the time between answers either: a counter that did not move gives none, so a rate computed from answers must
 * divide by the time measured between them. The wait between reads suspends, so under `runTest` it passes on virtual
 * time. Reads block the collector's thread under any watch; add `flowOn` with a dispatcher meant for blocking work to
 * read elsewhere.
 *
 * @param maxBytes the largest file read; a larger one is [FileError.TooLarge]. A procfs or sysfs file reports a size
 *   of 0 and is read to its end, so this bounds what it gives.
 * @param trusted reads as [readText] with `trusted` does, refusing a file or folder someone besides you may change.
 * @param watch how a change is noticed: the system's watch, a lookup on a timer, or a read on a timer.
 * @return a cold flow: each collector watches and reads on its own.
 */
public fun Path.textChanges(
    maxBytes: Long,
    trusted: Boolean = false,
    watch: TextWatch,
): Flow<Result<String, FileError>> = when (watch) {
    is Watch -> everyChange(watch).map { answer ->
        val unwatchable = (answer as? Result.Error)?.error as? FileError.Unwatchable
        if (unwatchable == null) readText(maxBytes, trusted) else Err(unwatchable)
    }
    is TextWatch.Read -> flow {
        while (true) {
            emit(readText(maxBytes, trusted))
            delay(watch.every)
        }
    }
}.distinctUntilChanged()
