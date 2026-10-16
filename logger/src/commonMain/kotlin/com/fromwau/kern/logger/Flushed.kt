package com.fromwau.kern.logger

/** What a [Logger.flush] that lost nothing found: everything written, or some sinks still holding entries. */
public sealed interface Flushed {
    /** Every sink wrote everything queued before the flush. */
    public data object FullyWritten : Flushed

    /**
     * The sinks that still hold entries queued before the flush, by name. Nothing was lost: they stay queued and are
     * written as each sink's [SinkFailure] says. Each name is one entry: a sink removed while still draining adds
     * into the entry of its name, as [Pending.TimedOut] when every sink of that name timed out, otherwise as
     * [Pending.GaveUp] with the error of the first that gave up.
     */
    public data class StillQueued(val pending: Map<String, Pending>) : Flushed
}

/** Why a sink still holds entries after a flush, and how many. */
public sealed interface Pending {
    /** The entries not yet written, a write still running included. */
    public val count: Int

    /**
     * A write failed under [SinkFailure.Retry] while the flush waited, with [error]. The flush stopped waiting for
     * this sink; its entries are retried after the backoff.
     */
    public data class GaveUp(
        override val count: Int,
        val error: SinkError,
    ) : Pending

    /**
     * The sink was still writing when the time ran out. Called from inside the sink's own write, a flush cannot wait
     * for that sink and reports what it has queued.
     */
    public data class TimedOut(override val count: Int) : Pending
}
