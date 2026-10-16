package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.AppendState
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.openAppend
import com.fromwau.kern.logger.BatchSink
import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.InternalKernApi
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.logger.lineRenderer
import com.fromwau.kern.logger.reportDroppedBySink
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How long a [FileSink] trusts that its path still names the file it has open. */
internal val FILE_CHECK_INTERVAL: Duration = 100.milliseconds

/**
 * Appends each entry to the file at [path], already resolved by you: kern opens exactly that path, never expands a
 * `~` and never picks a directory of its own. Missing parent directories are created.
 *
 * ```kotlin
 * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(FileSink(dirs.state / "app.log"))) }
 *     .onError { error -> Log.tag("App").w(error) { "no log file" } }
 * ```
 *
 * A lone entry is appended at once; entries waiting in the queue go out together, up to 256 lines in one write. Each
 * write holds only whole lines, so two processes logging to one local file never tear each other's lines. A failed
 * write fails for all its lines, which [onFailure] retries or drops together.
 *
 * When logrotate moves the file away or someone deletes it, the sink notices on its next write, looking at most once
 * every ~100 ms, and starts a new file at [path]. Lines written to a moved file before then stay in it. Lines written
 * to a deleted file in the ~100 ms before the deletion was noticed, up to [capacity] of them, are written again at
 * the start of the new file; any beyond that are counted as dropped. Lines written before that window went with the
 * deleted file: they are neither written again nor counted as dropped. The JVM on macOS cannot tell a deleted file
 * from a moved one, so there the lines of that window are counted as dropped, after a move as well, even though the
 * moved file kept them. The JVM on Windows opens the file so
 * that no other process can move or delete it until the sink is closed.
 *
 * A failure is [SinkError.Dropped] carrying kern:fs's [FileError]: [FileError.WriteFailed] with a reason for a
 * refused write, such as on a full disk, and [FileError.NotRegularFile] for a directory or device at [path]. By
 * default the entry is retried every second until it is written. A close that cannot write the lines of a deleted
 * file again fails with the same error, and counts those lines as dropped.
 *
 * @param path the file to append to, a kotlinx-io `Path`.
 * @param format [LogFormat.JSON] for a log shipper, [LogFormat.TEXT] for a person.
 * @param name names the sink in errors and health; unique within a logger's config.
 * @param onFailure what happens to an entry the file refused: retried after a second by default. For a path the user
 *   typed, pass [SinkFailure.Drop], so a path that cannot be opened does not hold up a close for its whole timeout.
 * @param capacity how many entries may wait for the file, and how many lines of a deleted file are written again.
 * @param drop which waiting entry gives way when more than [capacity] wait.
 */
@OptIn(InternalKernApi::class)
public class FileSink(
    public val path: Path,
    public val format: LogFormat = LogFormat.TEXT,
    override val name: String = "file:$path",
    override val onFailure: SinkFailure = SinkFailure.Retry(),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
) : BatchSink {
    // Test seams, set before the sink is configured. Kept out of the constructor, which a user's mistyped call lists.
    internal var timeSource: TimeSource = TimeSource.Monotonic
    internal var openFile: (Path) -> Result<AppendFile, FileError> = { it.openAppend() }

    // One FileSink may be in two loggers, each with its own worker.
    private val lock = reentrantLock()
    private val render = lineRenderer(format)
    private var file: AppendFile? = null

    // Set whenever a file is opened, and read only while one is.
    private lateinit var nextCheck: TimeMark

    // Set by a failed append, which may have left part of a line behind for the next one to end.
    private var lastAppendFailed = false

    // The lines appended since the path was last seen naming the open file, to write again if the file was deleted.
    private val kept = ArrayDeque<Line>()
    private var lastKept: TimeMark? = null
    private var unkept: Gap? = null
    private var rewriteKept = false

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> =
        appendLines(listOf(line(entry, config)))

    override fun writeBatch(entries: List<LogEntry>, config: SinkConfig): EmptyResult<SinkError> =
        appendLines(entries.map { line(it, config) })

    private fun line(entry: LogEntry, config: SinkConfig) =
        Line("${render(entry, config.timestamp)}\n", entry.timestamp)

    private fun appendLines(lines: List<Line>): EmptyResult<SinkError> {
        val failure = lock.withLock {
            forgetKeptAfterQuietSpell()
            append(lines)
        }
        return if (failure == null) Ok(Unit) else Err(SinkError.Dropped(failure))
    }

    override fun close(): EmptyResult<SinkError> {
        val failure = lock.withLock {
            forgetKeptAfterQuietSpell()
            file?.let { settleKept(it.state()) }
            closeHandle()
            // No later write may come to carry them, so they are written now or counted as dropped.
            if (rewriteKept) rewriteKeptNow() else null
        }
        return if (failure == null) Ok(Unit) else Err(SinkError.Dropped(failure))
    }

    /** Writes the kept lines to a new file at [path], or counts them as dropped; returns why that failed, or null. */
    private fun rewriteKeptNow(): FileError? {
        val failure = when (val opened = openFile(path)) {
            is Result.Success -> {
                val handle = opened.value
                val appended = handle.append(handle.pendingText().encodeToByteArray())
                handle.close()
                appended.errorOrNull()
            }
            is Result.Error -> opened.error
        }
        rewriteKept = false
        lastAppendFailed = failure != null
        if (failure == null) forgetKept() else dropKept()
        return failure
    }

    /** Appends [lines] in one write, after what [pendingText] says must come first. */
    private fun append(lines: List<Line>): FileError? {
        val handle = when (val opened = open()) {
            is Result.Success -> opened.value
            is Result.Error -> {
                lastAppendFailed = true
                return opened.error
            }
        }
        val text = buildString {
            append(handle.pendingText())
            lines.forEach { append(it.text) }
        }
        val failure = handle.append(text.encodeToByteArray()).errorOrNull()
        if (failure != null) {
            // The handle goes too, so the next attempt reopens and a deleted directory recovers once recreated.
            lastAppendFailed = true
            settleKept(handle.state())
            closeHandle()
            return failure
        }
        lastAppendFailed = false
        rewriteKept = false
        lines.forEach(::keep)
        return null
    }

    private fun open(): Result<AppendFile, FileError> {
        file?.let { open ->
            if (nextCheck.hasNotPassedNow()) return Ok(open)
            val state = open.state()
            if (state == AppendState.Same) {
                forgetKept()
                nextCheck = timeSource.markNow() + FILE_CHECK_INTERVAL
                return Ok(open)
            }
            settleKept(state)
        }

        closeHandle()
        val opened = openFile(path)
        if (opened is Result.Success) {
            file = opened.value
            nextCheck = timeSource.markNow() + FILE_CHECK_INTERVAL
        }
        return opened
    }

    /**
     * Lets go of kept lines older than the check interval, so a delete noticed after a quiet spell never brings them
     * back. Lines still waiting to be written again have reached no file, so they stay.
     */
    private fun forgetKeptAfterQuietSpell() {
        if (rewriteKept) return
        if (lastKept?.let { it.elapsedNow() > FILE_CHECK_INTERVAL } == true) forgetKept()
    }

    private fun keep(line: Line) {
        lastKept = timeSource.markNow()
        kept.addLast(line)
        if (kept.size > capacity) {
            val oldest = kept.removeFirst()
            unkept = unkept.plusLine(oldest.at)
        }
    }

    /** Decides what becomes of the kept lines, given what [state] says happened to the file they went to. */
    private fun settleKept(state: AppendState) {
        // Lines waiting to be written again have reached no file since, whatever became of this one.
        if (rewriteKept) return
        when (state) {
            AppendState.Deleted -> {
                rewriteKept = kept.isNotEmpty()
                unkept?.report()
                unkept = null
            }
            AppendState.Same, AppendState.Moved -> forgetKept()
            AppendState.Unknown -> dropKept()
        }
    }

    /** Counts every kept line, and those already let go past [capacity], as dropped. */
    private fun dropKept() {
        kept.fold(unkept) { gap, line -> gap.plusLine(line.at) }?.report()
        forgetKept()
    }

    private fun forgetKept() {
        kept.clear()
        unkept = null
    }

    /** What goes into this file ahead of the next line: the end of a torn line, then the kept lines to write again. */
    private fun AppendFile.pendingText(): String = buildString {
        if (lastAppendFailed && !endsWithNewline()) append('\n')
        if (rewriteKept) kept.forEach { append(it.text) }
    }

    // The torn line stays as it is, since truncating could cut another process's entries; ending it keeps the next
    // entry readable on a line of its own. A file this sink may only append to has no torn line it could see.
    private fun AppendFile.endsWithNewline(): Boolean = when (val last = lastByte()) {
        is Result.Success -> last.value?.let { it == '\n'.code.toByte() } ?: true
        is Result.Error -> true
    }

    private fun closeHandle() {
        file?.close()
        file = null
    }

    private class Line(val text: String, val at: Instant)

    /** Lines that never reached a file, between the first and the last one's timestamp. */
    private data class Gap(val count: Long, val from: Instant, val to: Instant) {
        fun report() = reportDroppedBySink(count, from, to)
    }

    private fun Gap?.plusLine(at: Instant): Gap =
        if (this == null) Gap(1, at, at) else Gap(count + 1, minOf(from, at), maxOf(to, at))
}
