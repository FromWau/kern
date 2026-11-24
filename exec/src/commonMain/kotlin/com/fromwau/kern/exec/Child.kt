package com.fromwau.kern.exec

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result

/**
 * Starts a child process. Take one as a parameter and a test can hand in a scripted one. A throw from an implementation
 * is not caught: it reaches the caller of [start].
 */
public fun interface Starter {
    /**
     * Starts [command] and returns at once with the running [Child], or the reason nothing started. Expected failures
     * are returned; a throw is a bug and propagates.
     */
    public fun start(command: Command): Result<Child, ExecError.StartError>
}

/** The [Starter] that runs real processes on the JVM and on Linux. */
public expect object SystemStarter : Starter {
    override fun start(command: Command): Result<Child, ExecError.StartError>
}

/**
 * A running child process. Nothing here stops the child except [stop]: not dropping this object, not cancelling the
 * caller, not this process exiting.
 *
 * Expected failures are returned, as a [ChildEvent.Failed] or an error; a throw is a bug and propagates. On the JVM a
 * throw on the thread [SystemStarter] reads the child or writes its input on always goes to that thread's uncaught
 * exception handler at once. It is also rethrown unchanged from [next], and from [Starter.start] when it kept the
 * start from answering, so a caller waiting there or calling later sees it too. A throw from an implementation of your
 * own is not caught either.
 */
public interface Child {
    /** The operating system's id of the child. */
    public val pid: Int

    /**
     * Blocks until the child has something to report and returns it, or null once nothing is left to come: after both
     * [ChildEvent.Exited] and [ChildEvent.OutputClosed] were returned, in whichever order they happened, or after
     * [ChildEvent.Failed]. Output is never dropped: what the child wrote while nobody called this comes later, in
     * order.
     */
    public fun next(): ChildEvent?

    /**
     * Sends [signal] to the child, [Signal.Terminate] by default, and returns without waiting for it to exit: wait
     * for [ChildEvent.Exited] through [next].
     *
     * The child runs in a process group of its own, and the signal goes to the whole group, so `sh -c "a | b"` stops
     * whole, and so does a background job the child left running after it exited. That lasts until [next] has
     * returned null after the last event, or until [detach]; from then on nothing is sent and the call succeeds, since
     * the group's id may be another process's by then. A child that already ended is not an error.
     *
     * This works after [ChildEvent.Failed] and after [detach] as before them, so a child you gave up waiting for can
     * still be ended.
     */
    public fun stop(signal: Signal = Signal.Terminate): EmptyResult<ExecError>

    /**
     * Stops reading the child without stopping it. From then on [next] returns null, while [stop] still reaches the
     * child. The child's output is still read and dropped until both of its streams close, so it never blocks on a
     * full pipe, and the child is reaped once it has ended and its streams closed.
     */
    public fun detach()
}

/** One thing a [Child] reports, in the order it happened. */
public sealed interface ChildEvent {
    /**
     * A piece of the child's output, decoded as UTF-8.
     *
     * @property text a slice of output, not necessarily a whole line.
     * @property stream the stream it came from.
     */
    public data class Output(val text: String, val stream: ChildStream) : ChildEvent

    /**
     * The child has ended. Its pid stays taken, by a zombie entry, until [Child.next] has returned null after the last
     * event or the child is detached, so [Child.stop] reaches its whole group and never another process that got the
     * same number. Read a child to the end, or detach it, so it leaves no zombie behind.
     *
     * @property code its exit status, or 128 plus the signal number when a signal killed it.
     */
    public data class Exited(val code: Int) : ChildEvent

    /**
     * The child closed both of its output streams. It may still be running: [Exited] follows when it ends.
     */
    public data object OutputClosed : ChildEvent

    /**
     * Reading from, writing to or waiting for the child failed. This is the last event: the child is not stopped, and
     * its output is read and dropped from then on. As after [Exited], it is reaped once [Child.next] has returned null
     * or it is detached, and [Child.stop] reaches it until then.
     *
     * @property error what went wrong.
     */
    public data class Failed(val error: ExecError) : ChildEvent
}

/** The two output streams of a child. */
public enum class ChildStream {
    /** Standard output. */
    Stdout,

    /** Standard error. */
    Stderr,
}

/** A signal to send to a child, by its Linux number. */
public sealed interface Signal {
    /** The signal's number. */
    public val number: Int

    /** SIGTERM (15): asks the child to end, which it may handle. */
    public data object Terminate : Signal {
        override val number: Int get() = 15
    }

    /** SIGKILL (9): ends the child at once; it cannot handle or ignore it. */
    public data object Kill : Signal {
        override val number: Int get() = 9
    }

    /** SIGINT (2): what Ctrl-C sends. */
    public data object Interrupt : Signal {
        override val number: Int get() = 2
    }

    /** SIGHUP (1): the terminal went away; daemons take it as a request to reload. */
    public data object Hangup : Signal {
        override val number: Int get() = 1
    }

    /** SIGQUIT (3): ends the child, usually with a core dump. */
    public data object Quit : Signal {
        override val number: Int get() = 3
    }

    /**
     * Any other signal by its Linux number, such as 10 for SIGUSR1.
     *
     * @property number the signal number.
     */
    public data class Number(override val number: Int) : Signal
}
