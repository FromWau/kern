package com.fromwau.kern.logger

import com.fromwau.kern.result.ResultError

/**
 * Why a sink did not take an entry, or did not close in time. A sink of your own reports its failures as [Dropped],
 * carrying its own typed reason.
 */
public sealed interface SinkError : ResultError {
    /**
     * The sink could not take the entry, for the reason in [error]: the sink's own typed error, such as kern:fs's
     * `FileError` from a `FileSink` or kern:terminal's `WriteError` from a console sink, or [Thrown] when its write or
     * close threw.
     */
    public data class Dropped(val error: ResultError) : SinkError

    /**
     * The sink was closed and cannot take the entry. No built-in sink returns it, since each reopens what it needs on
     * the next entry; it is there for a sink of your own that cannot. [InstalledSink.close] also returns it for a
     * handle that is [SinkState.Closed] or [SinkState.Abandoned].
     */
    public data object ClosedAlready : SinkError

    /**
     * A close ran out of time with [pending] entries not yet written, a write still running then included. It drops
     * them, each counted once in [SinkHealth.dropped], all but a write still running, which may yet land. The sink is
     * closed once that write returns. Every later close counts the dropped entries in [Lost].
     *
     * Called from inside the sink's own write, a close cannot wait for that sink. It reports what is queued, drops
     * nothing and leaves the sink open.
     */
    public data class TimedOut(val pending: Int) : SinkError

    /**
     * A close found that [count] entries were lost under the sink's name since it was first added, a sink removed and
     * added back included: a full queue, a [SinkFailure.Drop] failure or its backoff, or lines a `FileSink` could not
     * write again. Every close reports the whole count, a loss a flush already reported included, so the last close
     * alone tells whether anything was lost. Entries a timed-out close drops are counted in its [TimedOut], and in
     * [Lost] from the next close on.
     */
    public data class Lost(val count: Long) : SinkError
}

/** What a sink's write or close threw, any [Throwable], an [Error] included, passed through as it is. */
public data class Thrown(val throwable: Throwable) : ResultError

/** Why [Logger.configure] refused a new config. Nothing changed. */
public sealed interface ConfigError : ResultError {
    /** Two sinks in the new list are called [name]. */
    public data class NameTaken(val name: String) : ConfigError

    /**
     * The sink [name] asked for something impossible, such as a capacity below one or a negative backoff, or reading
     * one of its properties threw.
     */
    public data class Invalid(
        val name: String,
        val reason: String,
    ) : ConfigError
}

/** Why [Logger.flush] or [Logger.awaitFlushed] returned an error: entries were lost, or the call could not wait. */
public sealed interface FlushError : ResultError {
    /**
     * Entries were lost since the last flush or close.
     *
     * @param lost how many entries were lost under each name since the last flush or close: a full queue, a
     *   [SinkFailure.Drop] failure or its backoff, or lines a `FileSink` could not write again. Only the names that
     *   lost something are listed; a sink removed by [Logger.configure] adds into the entry of its name. A flush
     *   reports each loss once, so the next flush returns `Ok` unless more were lost.
     * @param stillQueued the sinks that still hold entries queued before the flush, by name, as [Flushed.StillQueued]
     *   lists them; empty when every sink wrote everything.
     */
    public data class Lost(
        val lost: Map<String, Long>,
        val stillQueued: Map<String, Pending>,
    ) : FlushError

    /**
     * The blocking [Logger.flush] cannot wait on JS and Wasm, which have one thread. The hold, if any, has ended;
     * call [Logger.awaitFlushed] to wait.
     */
    public data object CannotWait : FlushError
}

/**
 * What went wrong for each sink that did not close cleanly, by name: [SinkError.TimedOut] or the error its own close
 * returned first, then [SinkError.Lost] when entries were lost under that name.
 *
 * ```kotlin
 * Log.close().onError { failed -> failed.errors["file:app.log"]?.let { System.err.println("app.log: $it") } }
 * ```
 *
 * Each name is one entry: a sink removed while still draining, or one that lost entries before it was removed, adds
 * into the entry of its name, with one [SinkError.TimedOut] and one [SinkError.Lost], each summed. Every name not
 * listed wrote everything, never lost anything and closed cleanly.
 */
public data class CloseError(val errors: Map<String, List<SinkError>>) : ResultError
