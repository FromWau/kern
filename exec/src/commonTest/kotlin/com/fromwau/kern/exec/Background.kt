package com.fromwau.kern.exec

import kotlin.time.Duration

/**
 * Runs [block] on a thread of its own and returns a wait for its result: what [block] returned, or null when it has
 * not returned within the given time. A block that never returns leaves its thread behind.
 */
expect fun <T : Any> inBackground(block: () -> T): (timeout: Duration) -> T?
