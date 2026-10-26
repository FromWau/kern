package com.fromwau.kern.logger

/** Where an [InstalledSink] is in its life, as [InstalledSink.state] shows it. */
public enum class SinkState {
    /** In its logger's config, taking entries. */
    Active,

    /** Removed from the config, still writing what it had queued; added back now, it is Active again. */
    Draining,

    /** Removed, done writing and closed for good; adding the sink back gives it a new handle. */
    Closed,

    /** Removed, and one write ran five seconds without returning, so the logger let go of it; what it held is lost. */
    Abandoned,
}
