package com.fromwau.kern.logger

import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether [LoggerConfig.file] appends each line to a file. */
public sealed interface FileOutput {
    public data object None : FileOutput

    /**
     * Appends each line to [path], already resolved by you: kern opens exactly that path, never expands a `~` and
     * never picks a directory of its own. Missing parent directories are created.
     *
     * @param onFailure what happens to an entry the file refuses, such as on a full disk or a deleted directory.
     * @param format how each line is written: [LogFormat.JSON] for a log shipper, whatever the console shows.
     */
    public data class Append(
        val path: Path,
        val onFailure: FileFailure = FileFailure.Retry(),
        val format: LogFormat = LogFormat.TEXT,
    ) : FileOutput
}

/** What the logger does with an entry the log file refuses. The console and the sinks get every entry either way. */
public sealed interface FileFailure {
    /**
     * Holds refused entries in memory and writes them, in order, once the file takes writes again. When the file
     * recovers after entries were dropped, a line saying how many and between which times is written in their
     * place.
     *
     * @param capacity how many entries are held at most.
     * @param drop which end gives way when [capacity] is reached.
     * @param backoff the least time between two attempts while the file is refusing, so a dead disk costs one
     *   failing write per interval rather than one per entry.
     */
    public data class Retry(
        val capacity: Int = 1_000,
        val drop: DropPolicy = DropPolicy.OLDEST,
        val backoff: Duration = 1.seconds,
    ) : FileFailure {
        init {
            require(capacity > 0) { "capacity must be positive, was $capacity; use FileFailure.Drop to hold none" }
            require(!backoff.isNegative()) { "backoff must not be negative, was $backoff" }
        }
    }

    /** Holds nothing: a refused entry is counted and lost, and the next entry tries the file again. */
    public data object Drop : FileFailure

    /**
     * Throws the error to the code that logged, after the console and the sinks have the entry. For audit logs,
     * where a lost entry is itself a failure.
     */
    public data object Throw : FileFailure
}

/** Which entries give way when a bounded buffer is full. */
public enum class DropPolicy {
    /** The oldest held entry, which keeps the most recent context. */
    OLDEST,

    /** The entry arriving, which keeps the lead-up to the failure. */
    NEWEST,
}
