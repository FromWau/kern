@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.fromwau.kern.exec

import com.fromwau.kern.exec.spawn.KERN_EXEC_STEP_CHDIR
import com.fromwau.kern.exec.spawn.KERN_EXEC_STEP_EXEC
import com.fromwau.kern.exec.spawn.kern_exec_eventfd
import com.fromwau.kern.exec.spawn.kern_exec_pending
import com.fromwau.kern.exec.spawn.kern_exec_pidfd_open
import com.fromwau.kern.exec.spawn.kern_exec_pipe
import com.fromwau.kern.exec.spawn.kern_exec_spawn
import com.fromwau.kern.exec.spawn.kern_exec_start_thread
import com.fromwau.kern.exec.spawn.kern_exec_take_wake
import com.fromwau.kern.exec.spawn.kern_exec_wait
import com.fromwau.kern.exec.spawn.kern_exec_wake
import com.fromwau.kern.exec.spawn.kern_exec_write_all
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EACCES
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.ESRCH
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NOCTTY
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.kill
import platform.posix.open
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.pthread_cond_broadcast
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.read
import platform.posix.strerror

private const val READ_SIZE = 64 * 1024

/**
 * Starts [command] as [program], the path [lookUp] gave for [name], with [environment] as `NAME=value` entries.
 * Returns once the program runs or with the reason it does not.
 */
internal fun startLinuxChild(
    command: Command,
    name: String,
    program: String,
    environment: List<ByteArray>,
): Result<Child, ExecError.StartError> {
    val fds = StartFds()
    val stdin = command.stdin
    fds.open(command)?.let { return fds.failed(it) }
    val events = ChildEvents(fds.wake)

    if (stdin is Stdin.Bytes) {
        val writer = fds.stdinWriter
        val started = startThread { writeStdin(writer, stdin.bytes, events) }
        if (started != 0) return fds.failed(ExecError.StartFailed(command.argv, reason(started)))
        fds.stdinWriter = -1
    }
    val started = startThread { spawnAndRead(command, name, program, environment, fds, events) }
    if (started != 0) return fds.failed(ExecError.StartFailed(command.argv, reason(started)))
    return events.awaitStart().map { pid -> LinuxChild(pid, events) }
}

private class LinuxChild(override val pid: Int, private val events: ChildEvents) : Child {
    override fun next(): ChildEvent? = events.next()

    override fun stop(signal: Signal): EmptyResult<ExecError> = events.stop(pid, signal)

    override fun detach() = events.detach()
}

/** The descriptors a start opens; each is -1 once closed or handed to the thread that owns it. */
private class StartFds {
    var stdinChild = -1
    var stdinWriter = -1
    var stdoutRead = -1
    var stdoutChild = -1
    var stderrRead = -1
    var stderrChild = -1
    var wake = -1

    /**
     * Opens every descriptor [command] needs, all close-on-exec, and a read end only for a piped stream. Returns null
     * or why nothing can start.
     */
    fun open(command: Command): ExecError.StartError? = memScoped {
        val pair = allocArray<IntVar>(2)
        if (command.stdin is Stdin.Bytes) {
            val failure = kern_exec_pipe(pair, 0)
            if (failure != 0) return ExecError.StartFailed(command.argv, reason(failure))
            stdinChild = pair[0]
            stdinWriter = pair[1]
        } else {
            stdinChild = open("/dev/null", O_RDONLY or O_CLOEXEC)
            if (stdinChild < 0) return ExecError.StartFailed(command.argv, reason(errno))
        }
        stdoutChild = when (val opened = openOutput(command, ChildStream.Stdout, command.stdout, pair)) {
            is Ok -> opened.value
            is Err -> return opened.error
        }
        stderrChild = when (val opened = openOutput(command, ChildStream.Stderr, command.stderr, pair)) {
            is Ok -> opened.value
            is Err -> return opened.error
        }
        wake = kern_exec_eventfd()
        if (wake < 0) return ExecError.StartFailed(command.argv, reason(errno))
        null
    }

    /**
     * The child's end of [stream], opened as [output] says; a pipe's read end goes into [stdoutRead] or [stderrRead].
     */
    private fun openOutput(
        command: Command,
        stream: ChildStream,
        output: Output,
        pair: CPointer<IntVar>,
    ): Result<Int, ExecError.StartError> {
        val fd = when (output) {
            Output.Pipe -> {
                val failure = kern_exec_pipe(pair, 1)
                if (failure != 0) return Err(ExecError.StartFailed(command.argv, reason(failure)))
                if (stream == ChildStream.Stdout) stdoutRead = pair[0] else stderrRead = pair[0]
                pair[1]
            }
            Output.Discard -> open("/dev/null", O_WRONLY or O_CLOEXEC)
            Output.Inherit -> fcntl(if (stream == ChildStream.Stdout) 1 else 2, F_DUPFD_CLOEXEC, 3)
            Output.ToStdout -> fcntl(stdoutChild, F_DUPFD_CLOEXEC, 3)
            is Output.File -> {
                val flags = O_WRONLY or O_CREAT or O_NOCTTY or O_CLOEXEC or if (output.append) O_APPEND else O_TRUNC
                val opened = open(command.fileOf(output), flags, NEW_FILE_MODE)
                if (opened < 0) return Err(ExecError.OutputFileFailed(output.path, stream, reason(errno)))
                opened
            }
        }
        return if (fd < 0) Err(ExecError.StartFailed(command.argv, reason(errno))) else Ok(fd)
    }

    fun closeReadEnds() {
        stdoutRead = closed(stdoutRead)
        stderrRead = closed(stderrRead)
    }

    fun closeChildEnds() {
        stdinChild = closed(stdinChild)
        stdoutChild = closed(stdoutChild)
        stderrChild = closed(stderrChild)
    }

    fun failed(error: ExecError.StartError): Err<ExecError.StartError> {
        closeChildEnds()
        stdinWriter = closed(stdinWriter)
        closeReadEnds()
        wake = closed(wake)
        return Err(error)
    }

    private fun closed(fd: Int): Int {
        if (fd >= 0) close(fd)
        return -1
    }
}

/** Runs on the reader thread: starts the child, hands the outcome to `start`, then reads the child to its end. */
private fun spawnAndRead(
    command: Command,
    name: String,
    program: String,
    environment: List<ByteArray>,
    fds: StartFds,
    events: ChildEvents,
) {
    val spawned = spawn(command, name, program, environment, fds)
    fds.closeChildEnds()
    when (spawned) {
        is Err -> {
            fds.closeReadEnds()
            events.startFailed(spawned)
        }
        is Ok -> {
            val pid = spawned.value
            events.started(pid)
            Reader(pid, kern_exec_pidfd_open(pid), fds.stdoutRead, fds.stderrRead, fds.wake, events).run()
        }
    }
}

private fun spawn(
    command: Command,
    name: String,
    program: String,
    environment: List<ByteArray>,
    fds: StartFds,
): Result<Int, ExecError.StartError> = memScoped {
    val error = alloc<IntVar>()
    val result = kern_exec_spawn(
        program,
        cStrings(command.argv.map { it.encodeToByteArray() }),
        cStrings(environment),
        command.workingDirectory?.toString(),
        fds.stdinChild,
        fds.stdoutChild,
        fds.stderrChild,
        if (command.newSession) 1 else 0,
        error.ptr,
    )
    if (result > 0) return Ok(result)
    val step = -result
    val failure = error.value
    val workingDirectory = command.workingDirectory
    Err(
        when {
            step == KERN_EXEC_STEP_CHDIR && (failure == ENOENT || failure == ENOTDIR) && workingDirectory != null ->
                ExecError.WorkingDirectoryMissing(workingDirectory)
            step == KERN_EXEC_STEP_EXEC && failure == ENOENT -> programMissing(command, name, program)
            step == KERN_EXEC_STEP_EXEC && failure == EACCES -> ExecError.NotExecutable(name)
            else -> ExecError.StartFailed(command.argv, reason(failure))
        },
    )
}

/** A null-terminated C array of [strings], each NUL-terminated, freed with this scope. */
private fun MemScope.cStrings(strings: List<ByteArray>): CPointer<CPointerVar<ByteVar>> {
    val array = allocArray<CPointerVar<ByteVar>>(strings.size + 1)
    strings.forEachIndexed { index, bytes ->
        val string = allocArray<ByteVar>(bytes.size + 1)
        bytes.forEachIndexed { at, byte -> string[at] = byte }
        string[bytes.size] = 0
        array[index] = string
    }
    array[strings.size] = null
    return array
}

/** Runs on a thread of its own, so a child that writes before it reads cannot block `start`. */
private fun writeStdin(fd: Int, bytes: ByteArray, events: ChildEvents) {
    val failure = if (bytes.isEmpty()) {
        kern_exec_write_all(fd, null, 0u)
    } else {
        bytes.usePinned { kern_exec_write_all(fd, it.addressOf(0), bytes.size.convert()) }
    }
    if (failure != 0) events.post(ChildEvent.Failed(ExecError.StdinFailed(reason(failure))))
}

/** One of the child's output pipes, read non-blocking; its fd is -1 once it reached end of file. */
private class OutputPipe(var fd: Int, val stream: ChildStream) {
    val decoder = Utf8Stream()
    val isOpen: Boolean get() = fd >= 0
}

/**
 * Reads the child's output and waits for its exit in one `poll` loop, so whatever the child wrote before it ended
 * is read and posted before [ChildEvent.Exited]. The exited child is reaped only once its output closed and its
 * events were read past the end, or it was detached: until then its pid, which is its process group's id, cannot be
 * reused, so `stop` reaches the whole group and never someone else's.
 * Owns and closes every descriptor it is given.
 */
private class Reader(
    private val pid: Int,
    private var pidfd: Int,
    stdout: Int,
    stderr: Int,
    private val wake: Int,
    private val events: ChildEvents,
) {
    private val pipes = listOf(OutputPipe(stdout, ChildStream.Stdout), OutputPipe(stderr, ChildStream.Stderr))
    private val buffer = ByteArray(READ_SIZE)
    private var exited = false
    private var held = false
    private var outputClosed = false

    fun run() {
        while (!exited || !outputClosed) {
            if (!outputClosed && pipes.none { it.isOpen }) {
                events.post(ChildEvent.OutputClosed)
                outputClosed = true
            } else if (outputClosed && pidfd < 0) {
                // Without a pidfd nothing can be polled for the exit, but with the output closed a blocking wait
                // holds nothing back.
                awaitExit()
            } else {
                awaitReady()
            }
        }
        events.awaitLetGo()
        release()
        events.end()
    }

    private fun awaitReady(): Unit = memScoped {
        val open = pipes.filter { it.isOpen }
        val watchesPidfd = !exited && pidfd >= 0
        val fds = open.map { it.fd } + listOfNotNull(pidfd.takeIf { watchesPidfd }) + wake
        val watched = allocArray<pollfd>(fds.size)
        fds.forEachIndexed { index, fd ->
            watched[index].fd = fd
            watched[index].events = POLLIN.toShort()
            watched[index].revents = 0
        }

        if (poll(watched, fds.size.convert(), -1) < 0) {
            if (errno != EINTR) giveUp(reason(errno))
            return
        }
        if (watched[fds.lastIndex].revents.toInt() != 0) kern_exec_take_wake(wake)
        // The exit first: awaitExit reads what the pipes hold at that moment anyway, all of it before Exited.
        if (watchesPidfd && watched[open.size].revents.toInt() != 0) {
            awaitExit()
            return
        }
        for ((index, pipe) in open.withIndex()) {
            if (watched[index].revents.toInt() != 0) {
                val _ = readOnce(pipe)
            }
        }
    }

    /**
     * Waits for the exit without reaping the child, then posts what the pipes hold at that moment, then the exit. Only
     * that much: a background job that keeps writing must not hold the exit back.
     */
    private fun awaitExit(): Unit = memScoped {
        val exitStatus = alloc<IntVar>()
        val signal = alloc<IntVar>()
        val failure = kern_exec_wait(pidfd, pid, 1, exitStatus.ptr, signal.ptr)
        exited = true
        if (failure != 0) {
            events.post(ChildEvent.Failed(ExecError.WaitFailed(pid, reason(failure))))
            return
        }
        held = true
        for (pipe in pipes) {
            var unread = if (pipe.isOpen) kern_exec_pending(pipe.fd) else 0
            while (unread > 0) {
                val count = readOnce(pipe, unread)
                if (count == 0) break
                unread -= count
            }
        }
        val code = exitCodeOf(exitStatus.value.takeIf { it >= 0 }, signal.value.takeIf { it >= 0 })
        events.post(ChildEvent.Exited(code))
    }

    /** Reads and posts up to [limit] bytes of [pipe], at most a buffer's worth. Returns how many; 0 for none. */
    private fun readOnce(pipe: OutputPipe, limit: Int = buffer.size): Int {
        val size = minOf(limit, buffer.size)
        while (true) {
            val count = buffer.usePinned { read(pipe.fd, it.addressOf(0), size.convert()) }.toInt()
            when {
                count > 0 -> {
                    post(pipe.decoder.feed(buffer, count), pipe.stream)
                    return count
                }
                count == 0 -> closePipe(pipe)
                errno == EINTR -> continue
                errno != EAGAIN -> {
                    events.post(ChildEvent.Failed(ExecError.ReadFailed(pipe.stream, reason(errno))))
                    closePipe(pipe)
                }
            }
            return 0
        }
    }

    private fun closePipe(pipe: OutputPipe) {
        post(pipe.decoder.end(), pipe.stream)
        close(pipe.fd)
        pipe.fd = -1
    }

    private fun post(text: String, stream: ChildStream) {
        if (text.isNotEmpty()) events.post(ChildEvent.Output(text, stream))
    }

    /** A failed poll leaves nothing to read by: report it, let go of the pipes, and still reap the child. */
    private fun giveUp(reason: String) {
        events.post(ChildEvent.Failed(ExecError.WaitFailed(pid, reason)))
        pipes.filter { it.isOpen }.forEach { closePipe(it) }
        if (!exited) awaitExit()
        outputClosed = true
    }

    /** Stops `stop` from signalling, then reaps the child, which frees its pid for reuse. */
    private fun release(): Unit = memScoped {
        events.release()
        if (held) {
            // Fails only when something else in this process reaped the child first, which leaves nothing to do.
            val _ = kern_exec_wait(pidfd, pid, 0, alloc<IntVar>().ptr, alloc<IntVar>().ptr)
        }
        if (pidfd >= 0) close(pidfd)
        pidfd = -1
    }
}

/**
 * The events the reader thread posts and [next] takes, behind one lock and condition. Also hands the outcome of the
 * start to the thread waiting in `start`, and sends [stop]'s signals under the same lock, so none is sent after the
 * reader let go of the child with [release]. A [ChildEvent.Failed] is the last event: the child is then read and
 * dropped, and let go of as after [ChildEvent.Exited].
 */
internal class ChildEvents(private val wake: Int) {
    private val lock = nativeHeap.alloc<pthread_mutex_t>().also { pthread_mutex_init(it.ptr, null) }
    private val changed = nativeHeap.alloc<pthread_cond_t>().also { pthread_cond_init(it.ptr, null) }

    @Suppress("unused")
    private val cleaner = createCleaner(lock to changed) { (lock, changed) ->
        pthread_cond_destroy(changed.ptr)
        pthread_mutex_destroy(lock.ptr)
        nativeHeap.free(changed)
        nativeHeap.free(lock)
    }

    private val queue = ArrayDeque<ChildEvent>()
    private var start: Result<Int, ExecError.StartError>? = null
    private var ended = false
    private var failed = false
    private var detached = false
    private var released = false
    private var finished = false
    private var exhausted = false

    fun started(pid: Int) = locked { start = Ok(pid) }

    /** Answers `start` with [failure] unless it was answered already, and ends the events. */
    fun startFailed(failure: Err<ExecError.StartError>) = locked {
        if (start == null) start = failure
        endLocked()
    }

    fun awaitStart(): Result<Int, ExecError.StartError> = locked {
        var outcome = start
        while (outcome == null) {
            pthread_cond_wait(changed.ptr, lock.ptr)
            outcome = start
        }
        outcome
    }

    /** Queues [event] unless the events already ended, by [end], a [ChildEvent.Failed] or [detach]. */
    fun post(event: ChildEvent) = locked {
        if (ended || failed || detached) return@locked
        queue.addLast(event)
        failed = event is ChildEvent.Failed
    }

    /** The reader is done: nothing more comes. */
    fun end() = locked { endLocked() }

    fun next(): ChildEvent? = locked {
        while (queue.isEmpty() && !finished && !ended && !failed && !detached) pthread_cond_wait(changed.ptr, lock.ptr)
        if (detached) return@locked null
        val event = queue.removeFirstOrNull()
        // Asked past the end: the reader may let go of the child.
        if (event == null) exhausted = true
        event
    }

    /**
     * Waits, on the reader thread once it posted the last event, until [next] was asked past the end or the child
     * was detached: until then [stop] still reaches the group, whatever the child left running.
     */
    fun awaitLetGo() = locked {
        finished = true
        // Before waiting: a next already waiting for an event is woken only by this.
        pthread_cond_broadcast(changed.ptr)
        while (!exhausted && !detached) pthread_cond_wait(changed.ptr, lock.ptr)
    }

    /**
     * Sends [signal] to the process group [pid] leads. Sends nothing once the child was [release]d: its pid may then
     * lead another process's group.
     */
    fun stop(pid: Int, signal: Signal): EmptyResult<ExecError> = locked {
        when {
            released -> Ok(Unit)
            kill(-pid, signal.number) == 0 || errno == ESRCH -> Ok(Unit)
            else -> Err(ExecError.StopFailed(pid, reason(errno)))
        }
    }

    /** The child is about to be reaped: [stop] sends nothing from then on. */
    fun release() = locked { released = true }

    fun detach() = locked {
        detached = true
        queue.clear()
        if (!ended) kern_exec_wake(wake)
    }

    private fun endLocked() {
        if (ended) return
        ended = true
        close(wake)
    }

    /** Runs [block] under the lock, then wakes every waiter, since most callers change what they wait for. */
    private inline fun <T> locked(block: () -> T): T {
        pthread_mutex_lock(lock.ptr)
        try {
            return block()
        } finally {
            pthread_cond_broadcast(changed.ptr)
            pthread_mutex_unlock(lock.ptr)
        }
    }
}

/** Runs [body] on a new detached thread. Returns 0 or the error that kept the thread from starting. */
private fun startThread(body: () -> Unit): Int {
    val reference = StableRef.create(body)
    val failure = kern_exec_start_thread(staticCFunction(::runThread), reference.asCPointer())
    if (failure != 0) reference.dispose()
    return failure
}

private fun runThread(argument: COpaquePointer?): COpaquePointer? {
    val reference = argument?.asStableRef<() -> Unit>() ?: return null
    val body = reference.get()
    reference.dispose()
    body()
    return null
}

private fun reason(errno: Int): String = strerror(errno)?.toKString() ?: "errno $errno"
