package com.fromwau.kern.terminal

/**
 * Runs [Terminal.afterWrites] blocks one after another instead of one inside another, so a block that calls
 * [Terminal.afterWrites] again, as a streaming loop does, never deepens the stack.
 */
internal class AfterWritesQueue {
    private val waiting = ArrayDeque<() -> Unit>()
    private var running = false

    /** Runs [block] now, or once the block running on this thread returns. A throw drops what still waits. */
    fun runOrQueue(block: () -> Unit) {
        if (running) {
            waiting.addLast(block)
            return
        }
        running = true
        try {
            block()
            while (waiting.isNotEmpty()) waiting.removeFirst()()
        } finally {
            waiting.clear()
            running = false
        }
    }
}

/** This thread's queue: a block from one thread must never run on another. */
internal expect fun afterWritesQueue(): AfterWritesQueue
