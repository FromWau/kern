package com.fromwau.kern.logger

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant
import kotlin.time.TimeSource

/** A line on its way to the log file, stamped for the gap line that may stand in for it. */
internal class FileLine(val timestamp: Instant, val text: String)

/** The log file: its open handle, and the entries it refused. [Logger] holds its lock around every call. */
internal class LogFile(timeSource: TimeSource) {
    private val retry = RetryBuffer<FileLine>(timeSource) { it.timestamp }

    // Where the held entries belong: the path of the last write, even while its handle is closed by a failure.
    private var target: Path? = null

    private var openPath: Path? = null
    private var openFile: AppendFile? = null

    // Set by a failed append, which may have left part of a line behind for the next one to end.
    private var lastAppendFailed = false

    val health: BufferHealth
        get() = retry.health

    val idle: Boolean
        get() = retry.idle

    /**
     * Appends [line], after whatever was held, as [output] says.
     *
     * @param gapLine renders the line that stands in for dropped entries.
     * @return the exception this attempt failed with, or null when it succeeded or was deferred.
     */
    fun write(line: FileLine, output: FileOutput.Append, gapLine: (Gap) -> String): Exception? {
        val path = output.path
        target = path
        val rule = when (val failure = output.onFailure) {
            is FileFailure.Retry -> RetryRule(failure.capacity, failure.drop, failure.backoff)
            FileFailure.Drop, FileFailure.Throw -> RetryRule.NONE
        }
        return retry.send(line, rule, { append(it.text, path) }, { append(gapLine(it), path) })
    }

    /**
     * Writes what is held one last time to the path it belongs to, then releases the handle.
     *
     * @return how many entries were lost and why, or null when nothing was.
     */
    fun close(drop: DropPolicy, gapLine: (Gap) -> String): Pair<Long, Exception>? {
        val path = target
        val lost = path?.let { retry.flushOrAbandon(drop, { append(it.text, path) }, { append(gapLine(it), path) }) }
        closeHandle()
        target = null
        return lost
    }

    /** The path held entries belong to, if any write has happened since the last [close]. */
    val path: Path?
        get() = target

    private fun append(line: String, path: Path) {
        try {
            val handle = fileFor(path)
            val text = if (lastAppendFailed && !endsWithNewline(path)) "\n$line\n" else "$line\n"
            handle.append(text.encodeToByteArray())
            lastAppendFailed = false
        } catch (e: Exception) {
            // The handle goes too, so the next attempt reopens and a deleted directory recovers once recreated.
            lastAppendFailed = true
            closeHandle()
            throw e
        }
    }

    // The torn line stays as it is, since truncating could cut another process's entries; ending it keeps the
    // next entry readable on a line of its own.
    private fun endsWithNewline(file: Path): Boolean {
        // A device such as /dev/full reports no size (-1), and there is no torn line to end in one anyway.
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: return true
        if (size <= 0L) return true

        return SystemFileSystem.source(file).buffered().use { source ->
            source.skip(size - 1)
            source.readByte() == '\n'.code.toByte()
        }
    }

    private fun fileFor(path: Path): AppendFile {
        openFile?.let { if (openPath == path) return it }

        closeHandle()
        path.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }

        return openAppendFile(path).also {
            openFile = it
            openPath = path
        }
    }

    private fun closeHandle() {
        try {
            openFile?.close()
        } catch (_: Exception) {
            // Already unusable; the next entry opens a fresh handle.
        }
        openFile = null
        openPath = null
    }
}
