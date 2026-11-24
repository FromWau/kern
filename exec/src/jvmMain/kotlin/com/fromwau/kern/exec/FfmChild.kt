package com.fromwau.kern.exec

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// <fcntl.h>, <sys/eventfd.h>, <sys/ioctl.h>, <spawn.h>, <signal.h>, <sys/wait.h>, <poll.h>, <errno.h> and the
// syscall number of pidfd_open: the same on x86_64 and aarch64 Linux.
private const val O_RDONLY = 0
private const val O_WRONLY = 1
private const val O_CREAT = 0x40
private const val O_NOCTTY = 0x100
private const val O_TRUNC = 0x200
private const val O_APPEND = 0x400
private const val F_DUPFD_CLOEXEC = 1030
private const val O_NONBLOCK = 0x800L
private const val O_CLOEXEC = 0x80000
private const val F_SETFL = 4
private const val EFD_NONBLOCK = 0x800
private const val EFD_CLOEXEC = 0x80000
private const val FIONREAD = 0x541BL
private const val POSIX_SPAWN_SETPGROUP = 0x02
private const val POSIX_SPAWN_SETSIGDEF = 0x04
private const val POSIX_SPAWN_SETSIGMASK = 0x08
private const val POSIX_SPAWN_SETSID = 0x80
private const val SIGPIPE = 13
private const val P_PID = 1
private const val P_PIDFD = 3
private const val WEXITED = 4
private const val WNOWAIT = 0x01000000
private const val CLD_EXITED = 1
private const val SYS_PIDFD_OPEN = 434L
private const val POLLIN: Short = 1
private const val ENOENT = 2
private const val ENOTDIR = 20
private const val ESRCH = 3
private const val EINTR = 4
private const val EINVAL = 22
private const val EAGAIN = 11
private const val EACCES = 13
private const val EPIPE = 32

// glibc's sizes are 80 for the file actions, 336 for the attributes, 128 for a sigset_t and a siginfo_t; the room
// beyond costs nothing.
private const val FILE_ACTIONS_BYTES = 256L
private const val SPAWN_ATTRIBUTES_BYTES = 1024L
private const val SIGSET_BYTES = 128L
private const val SIGINFO_BYTES = 128L
private const val SIGINFO_CODE = 8L
private const val SIGINFO_STATUS = 24L
private const val POLLFD_BYTES = 8L

private const val READ_SIZE = 64 * 1024

/** The libc calls a child needs, each capturing errno where its failure is reported. */
private object Libc {
    private val linker = Linker.nativeLinker()
    private val errnoState = Linker.Option.captureCallState("errno")
    val stateLayout: StructLayout = Linker.Option.captureStateLayout()
    private val errnoHandle: VarHandle = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))

    fun errno(state: MemorySegment): Int = errnoHandle.get(state, 0L) as Int

    /** libc's `char **environ`. */
    val environ: MemorySegment = linker.defaultLookup().find("environ").orElseThrow().reinterpret(ADDRESS.byteSize())

    private fun handle(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

    private fun optional(name: String, descriptor: FunctionDescriptor): MethodHandle? =
        linker.defaultLookup().find(name).map { linker.downcallHandle(it, descriptor) }.orElse(null)

    private fun variadicFrom(index: Int) = Linker.Option.firstVariadicArg(index)

    val pipe2 = handle("pipe2", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), errnoState)
    val open = handle(
        "open",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
        errnoState,
        variadicFrom(2),
    )
    val fcntl = handle(
        "fcntl",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG),
        errnoState,
        variadicFrom(2),
    )
    val eventfd = handle("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), errnoState)
    val close = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    val read = handle("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), errnoState)
    val write = handle("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), errnoState)
    val poll = handle("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), errnoState)
    val ioctl = handle(
        "ioctl",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS),
        errnoState,
        variadicFrom(2),
    )
    val syscall = handle(
        "syscall",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
        errnoState,
        variadicFrom(1),
    )
    val waitid = handle("waitid", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), errnoState)
    val kill = handle("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), errnoState)
    val strerror = handle("strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT))
    val sigemptyset = handle("sigemptyset", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val sigaddset = handle("sigaddset", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))

    val posixSpawn = handle(
        "posix_spawn",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
    )
    val fileActionsInit = handle("posix_spawn_file_actions_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val fileActionsDestroy = handle("posix_spawn_file_actions_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val addDup2 = handle(
        "posix_spawn_file_actions_adddup2",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
    )

    // glibc 2.29 and 2.34 on: without the first a working folder cannot be set, without the second an inherited
    // descriptor that is not close-on-exec stays open in the child.
    val addChdir = optional("posix_spawn_file_actions_addchdir_np", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val addCloseFrom = optional(
        "posix_spawn_file_actions_addclosefrom_np",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
    )

    val attributesInit = handle("posix_spawnattr_init", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val attributesDestroy = handle("posix_spawnattr_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    val setFlags = handle("posix_spawnattr_setflags", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_SHORT))
    val setPgroup = handle("posix_spawnattr_setpgroup", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    val setSigDefault = handle("posix_spawnattr_setsigdefault", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
    val setSigMask = handle("posix_spawnattr_setsigmask", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
}

/** Loads every libc call, which throws when the JDK refuses this library native access. */
internal fun loadLibc() {
    val _ = Libc.close
}

/** This process's variables as the bytes libc holds them, read from `environ`; for a name set twice the first. */
internal fun inheritedEnvironment(): Map<String, ByteArray> {
    val variables = LinkedHashMap<String, ByteArray>()
    val entries = Libc.environ.get(ADDRESS, 0).reinterpret(Long.MAX_VALUE)
    var index = 0L
    while (true) {
        val entry = entries.getAtIndex(ADDRESS, index++)
        if (entry == MemorySegment.NULL) return variables
        val text = entry.reinterpret(Long.MAX_VALUE)
        var length = 0L
        while (text.get(JAVA_BYTE, length) != 0.toByte()) length++
        val (name, value) = splitEntry(text.asSlice(0, length).toArray(JAVA_BYTE)) ?: continue
        if (name !in variables) variables[name] = value
    }
}

/** The points in a child's life where [testProbe] is called. */
internal enum class FfmStep {
    ThreadStart,
    Spawn,
    Started,
    Read,
    WriteStdin,
    Wait,
    Reaped,
    Stop,
    Wake,
}

/** Called at each [FfmStep] of the children started while it is set: a test throws from it as a bug in kern would. */
internal val testProbe = AtomicReference<((FfmStep) -> Unit)?>(null)

/** Starts [command] as [program], the path [lookUp] gave, with [environment] as `NAME=value` entries. */
internal fun startFfmChild(
    command: Command,
    program: String,
    environment: List<ByteArray>,
): Result<Child, ExecError.StartError> {
    val fds = StartFds()
    val events: FfmEvents
    try {
        val stdin = command.stdin
        fds.open(command)?.let { return fds.failed(it) }
        events = FfmEvents(fds.wake, testProbe.get())
        if (stdin is Stdin.Bytes) {
            val writer = fds.stdinWriter
            daemon("stdin", events) { writeStdin(writer, stdin.bytes, events) }
                ?.let { return fds.failed(ExecError.StartFailed(command.argv, it)) }
            fds.stdinWriter = -1
        }
        // From here on the reader thread owns every descriptor left.
        daemon("reader", events) { spawnAndRead(command, program, environment, fds, events) }
            ?.let { return fds.failed(ExecError.StartFailed(command.argv, it)) }
    } catch (bug: Throwable) {
        fds.closeAll()
        throw bug
    }
    return events.awaitStart().map { pid -> FfmChild(pid, events) }
}

private class FfmChild(override val pid: Int, private val events: FfmEvents) : Child {
    override fun next(): ChildEvent? = events.next()

    override fun stop(signal: Signal): EmptyResult<ExecError> = events.stop(pid, signal)

    override fun detach() = events.detach()
}

/**
 * Runs [body] on a new daemon thread, so a child still running never keeps this process from exiting. Returns null, or
 * why the system started no thread.
 */
private fun daemon(role: String, events: FfmEvents, body: () -> Unit): String? {
    val thread = Thread(body, "kern-exec-$role").apply { isDaemon = true }
    return try {
        events.reached(FfmStep.ThreadStart)
        thread.start()
        null
    } catch (refused: OutOfMemoryError) {
        // How the JVM reports the errno of pthread_create: the system's thread limit, or no memory for a stack.
        refused.message ?: refused.toString()
    }
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
    fun open(command: Command): ExecError.StartError? = Arena.ofConfined().use { arena ->
        val state = arena.allocate(Libc.stateLayout)
        val pair = arena.allocate(JAVA_INT, 2)
        if (command.stdin is Stdin.Bytes) {
            val failure = pipe(state, pair, nonblockingRead = false)
            if (failure != 0) return ExecError.StartFailed(command.argv, reason(failure))
            stdinChild = pair.getAtIndex(JAVA_INT, 0)
            stdinWriter = pair.getAtIndex(JAVA_INT, 1)
        } else {
            stdinChild = Libc.open.invokeWithArguments(state, arena.allocateFrom("/dev/null"), O_RDONLY or O_CLOEXEC, 0)
                as Int
            if (stdinChild < 0) return ExecError.StartFailed(command.argv, reason(Libc.errno(state)))
        }
        stdoutChild = when (val opened = openOutput(arena, state, pair, command, ChildStream.Stdout, command.stdout)) {
            is Ok -> opened.value
            is Err -> return opened.error
        }
        stderrChild = when (val opened = openOutput(arena, state, pair, command, ChildStream.Stderr, command.stderr)) {
            is Ok -> opened.value
            is Err -> return opened.error
        }
        wake = Libc.eventfd.invokeWithArguments(state, 0, EFD_CLOEXEC or EFD_NONBLOCK) as Int
        if (wake < 0) return ExecError.StartFailed(command.argv, reason(Libc.errno(state)))
        null
    }

    /**
     * The child's end of [stream], opened as [output] says; a pipe's read end goes into [stdoutRead] or [stderrRead].
     */
    private fun openOutput(
        arena: Arena,
        state: MemorySegment,
        pair: MemorySegment,
        command: Command,
        stream: ChildStream,
        output: Output,
    ): Result<Int, ExecError.StartError> {
        val fd = when (output) {
            Output.Pipe -> {
                val failure = pipe(state, pair, nonblockingRead = true)
                if (failure != 0) return Err(ExecError.StartFailed(command.argv, reason(failure)))
                if (stream == ChildStream.Stdout) {
                    stdoutRead = pair.getAtIndex(JAVA_INT, 0)
                } else {
                    stderrRead = pair.getAtIndex(JAVA_INT, 0)
                }
                pair.getAtIndex(JAVA_INT, 1)
            }
            Output.Discard ->
                Libc.open.invokeWithArguments(state, arena.allocateFrom("/dev/null"), O_WRONLY or O_CLOEXEC, 0) as Int
            Output.Inherit -> duplicate(state, if (stream == ChildStream.Stdout) 1 else 2)
            Output.ToStdout -> duplicate(state, stdoutChild)
            is Output.File -> {
                val flags = O_WRONLY or O_CREAT or O_NOCTTY or O_CLOEXEC or if (output.append) O_APPEND else O_TRUNC
                val path = arena.allocateFrom(command.fileOf(output))
                val opened = Libc.open.invokeWithArguments(state, path, flags, NEW_FILE_MODE) as Int
                if (opened < 0) {
                    return Err(ExecError.OutputFileFailed(output.path, stream, reason(Libc.errno(state))))
                }
                opened
            }
        }
        return if (fd < 0) Err(ExecError.StartFailed(command.argv, reason(Libc.errno(state)))) else Ok(fd)
    }

    /** A close-on-exec copy of [fd] above the standard three, or -1 with errno in [state]. */
    private fun duplicate(state: MemorySegment, fd: Int): Int =
        Libc.fcntl.invokeWithArguments(state, fd, F_DUPFD_CLOEXEC, 3L) as Int

    /** A pipe into [pair] whose both ends close on exec. Returns 0 or errno. */
    private fun pipe(state: MemorySegment, pair: MemorySegment, nonblockingRead: Boolean): Int {
        if (Libc.pipe2.invokeWithArguments(state, pair, O_CLOEXEC) as Int != 0) return Libc.errno(state)
        if (nonblockingRead && Libc.fcntl.invokeWithArguments(state, pair.getAtIndex(JAVA_INT, 0), F_SETFL, O_NONBLOCK)
                as Int != 0
        ) {
            val failure = Libc.errno(state)
            closeFd(pair.getAtIndex(JAVA_INT, 0))
            closeFd(pair.getAtIndex(JAVA_INT, 1))
            return failure
        }
        return 0
    }

    fun closeChildEnds() {
        closeFd(stdinChild.also { stdinChild = -1 })
        closeFd(stdoutChild.also { stdoutChild = -1 })
        closeFd(stderrChild.also { stderrChild = -1 })
    }

    fun closeReadEnds() {
        closeFd(stdoutRead.also { stdoutRead = -1 })
        closeFd(stderrRead.also { stderrRead = -1 })
    }

    fun closeAll() {
        closeChildEnds()
        closeReadEnds()
        closeFd(stdinWriter.also { stdinWriter = -1 })
        closeFd(wake.also { wake = -1 })
    }

    fun failed(error: ExecError.StartError): Err<ExecError.StartError> {
        closeAll()
        return Err(error)
    }
}

/**
 * Runs on the reader thread: starts the child, hands the outcome to `start`, then reads the child to its end. A throw
 * is a bug in kern, [FfmEvents.crashed]; the descriptors are still closed and the child still reaped.
 */
private fun spawnAndRead(
    command: Command,
    program: String,
    environment: List<ByteArray>,
    fds: StartFds,
    events: FfmEvents,
) {
    var pid = -1
    var reader: Reader? = null
    try {
        events.reached(FfmStep.Spawn)
        val spawned = spawn(command, program, environment, fds)
        fds.closeChildEnds()
        when (spawned) {
            is Err -> {
                fds.closeReadEnds()
                events.startFailed(spawned)
            }
            is Ok -> {
                pid = spawned.value
                events.started(pid)
                events.reached(FfmStep.Started)
                Arena.ofConfined().use { arena ->
                    Reader(arena, pid, fds.stdoutRead, fds.stderrRead, fds.wake, events).also { reader = it }.run()
                }
            }
        }
    } catch (bug: Throwable) {
        // In a finally, as a throw from the uncaught exception handler leaves the child to clean up all the same.
        try {
            events.crashed(bug)
        } finally {
            // A reader lets go of the child even when it throws.
            if (reader == null) {
                fds.closeChildEnds()
                // Nothing reads the pipes: a child that writes gets SIGPIPE instead of blocking forever.
                fds.closeReadEnds()
                if (pid >= 0) {
                    try {
                        reapWhenEnded(pid, events)
                    } catch (again: Throwable) {
                        try {
                            events.crashedAgain(bug, again)
                        } finally {
                            reapWhenEnded(pid, events, probed = false)
                        }
                    }
                }
            }
        }
    } finally {
        events.end()
    }
}

/**
 * Waits for the child to end without a reader, then reaps it so it leaves no zombie. `stop` reaches it until it ended
 * and is shut off before the reap, so it never signals another process that got the same number. Without [probed] it
 * passes no [FfmStep], so it reaps a child after a throw at one of them.
 */
private fun reapWhenEnded(pid: Int, events: FfmEvents, probed: Boolean = true) = Arena.ofConfined().use { arena ->
    val state = arena.allocate(Libc.stateLayout)
    val siginfo = arena.allocate(SIGINFO_BYTES, 8)
    fun waitFor(options: Int): Int {
        if (probed) events.reached(FfmStep.Wait)
        while (true) {
            if (Libc.waitid.invokeWithArguments(state, P_PID, pid, siginfo, options) as Int == 0) return 0
            val errno = Libc.errno(state)
            if (errno != EINTR) return errno
        }
    }
    val ended = waitFor(WEXITED or WNOWAIT)
    events.release()
    if (ended == 0) {
        val _ = waitFor(WEXITED)
    }
}

/** Starts [program] in a process group of its own with the child ends of [fds] as its stdin, stdout and stderr. */
private fun spawn(
    command: Command,
    program: String,
    environment: List<ByteArray>,
    fds: StartFds,
): Result<Int, ExecError.StartError> =
    Arena.ofConfined().use { arena ->
        val workingDirectory = command.workingDirectory
        val addChdir = Libc.addChdir
        if (workingDirectory != null && addChdir == null) {
            return Err(
                ExecError.Unsupported(
                    "this system's C library cannot start a child in another folder; glibc 2.29 and later can",
                ),
            )
        }
        val actions = arena.allocate(FILE_ACTIONS_BYTES, 16)
        val attributes = arena.allocate(SPAWN_ATTRIBUTES_BYTES, 16)
        var failure = Libc.fileActionsInit.invokeWithArguments(actions) as Int
        if (failure != 0) return Err(ExecError.StartFailed(command.argv, reason(failure)))
        try {
            failure = Libc.attributesInit.invokeWithArguments(attributes) as Int
            if (failure != 0) return Err(ExecError.StartFailed(command.argv, reason(failure)))
            try {
                val directory = workingDirectory?.toString()
                failure = configure(arena, actions, attributes, fds, directory, addChdir, command.newSession)
                if (failure != 0) return Err(ExecError.StartFailed(command.argv, reason(failure)))
                val pid = arena.allocate(JAVA_INT)
                failure = Libc.posixSpawn.invokeWithArguments(
                    pid,
                    arena.allocateFrom(program),
                    actions,
                    attributes,
                    cStrings(arena, command.argv.map { it.encodeToByteArray() }),
                    cStrings(arena, environment),
                ) as Int
                if (failure == 0) return Ok(pid.get(JAVA_INT, 0))
            } finally {
                val _ = Libc.attributesDestroy.invokeWithArguments(attributes)
            }
        } finally {
            val _ = Libc.fileActionsDestroy.invokeWithArguments(actions)
        }
        val name = command.argv.firstOrNull().orEmpty()
        Err(
            when {
                // The folder was checked before the start, so only one removed since makes the chdir fail.
                (failure == ENOENT || failure == ENOTDIR) && workingDirectory != null &&
                    !File(workingDirectory.toString()).isDirectory ->
                    ExecError.WorkingDirectoryMissing(workingDirectory)
                failure == ENOENT -> programMissing(command, name, program)
                failure == EACCES -> ExecError.NotExecutable(name)
                else -> ExecError.StartFailed(command.argv, reason(failure))
            },
        )
    }

/**
 * Sets the child's three standard descriptors, closes every other one this process did not mark close-on-exec, moves
 * it to [workingDirectory], puts it in a process group of its own, or a session of its own with [newSession], gives it
 * an empty signal mask and SIGPIPE's default action. Returns 0 or the error of the step that failed.
 */
private fun configure(
    arena: Arena,
    actions: MemorySegment,
    attributes: MemorySegment,
    fds: StartFds,
    workingDirectory: String?,
    addChdir: MethodHandle?,
    newSession: Boolean,
): Int {
    for ((index, fd) in listOf(fds.stdinChild, fds.stdoutChild, fds.stderrChild).withIndex()) {
        val failure = Libc.addDup2.invokeWithArguments(actions, fd, index) as Int
        if (failure != 0) return failure
    }
    Libc.addCloseFrom?.let { addCloseFrom ->
        val failure = addCloseFrom.invokeWithArguments(actions, 3) as Int
        if (failure != 0) return failure
    }
    if (workingDirectory != null && addChdir != null) {
        val failure = addChdir.invokeWithArguments(actions, arena.allocateFrom(workingDirectory)) as Int
        if (failure != 0) return failure
    }
    val noSignals = arena.allocate(SIGSET_BYTES, 8)
    val pipeOnly = arena.allocate(SIGSET_BYTES, 8)
    val _ = Libc.sigemptyset.invokeWithArguments(noSignals)
    val _ = Libc.sigemptyset.invokeWithArguments(pipeOnly)
    val _ = Libc.sigaddset.invokeWithArguments(pipeOnly, SIGPIPE)
    // A new session's leader leads its process group too, and may not call setpgid, which SETPGROUP would.
    val group = if (newSession) POSIX_SPAWN_SETSID else POSIX_SPAWN_SETPGROUP
    val flags = (group or POSIX_SPAWN_SETSIGDEF or POSIX_SPAWN_SETSIGMASK).toShort()
    for (step in listOf(
        { Libc.setFlags.invokeWithArguments(attributes, flags) },
        { Libc.setPgroup.invokeWithArguments(attributes, 0) },
        { Libc.setSigDefault.invokeWithArguments(attributes, pipeOnly) },
        { Libc.setSigMask.invokeWithArguments(attributes, noSignals) },
    )) {
        val failure = step() as Int
        if (failure != 0) return failure
    }
    return 0
}

/** A null-terminated C array of [strings], freed with [arena]. */
private fun cStrings(arena: Arena, strings: List<ByteArray>): MemorySegment {
    val array = arena.allocate(ADDRESS, strings.size + 1L)
    strings.forEachIndexed { index, bytes ->
        // Zeroed, so the byte after the copy ends the string.
        val string = arena.allocate(bytes.size + 1L).copyFrom(MemorySegment.ofArray(bytes))
        array.setAtIndex(ADDRESS, index.toLong(), string)
    }
    array.setAtIndex(ADDRESS, strings.size.toLong(), MemorySegment.NULL)
    return array
}

/**
 * Runs on a thread of its own, so a child that writes before it reads cannot block `start`. A child that closed its
 * input gives EPIPE, which ends the write and is no error; the JVM keeps SIGPIPE from ending this process.
 */
private fun writeStdin(fd: Int, bytes: ByteArray, events: FfmEvents) {
    val failure = try {
        events.reached(FfmStep.WriteStdin)
        Arena.ofConfined().use { arena -> writeAll(arena, fd, bytes) }
    } catch (bug: Throwable) {
        // Recorded before the close below, which may let the child end.
        events.crashed(bug)
        return
    } finally {
        closeFd(fd)
    }
    if (failure != null) events.post(ChildEvent.Failed(ExecError.StdinFailed(failure)))
}

/** Writes all of [bytes] to [fd]. Returns null when done or the child closed its end, else the error's message. */
private fun writeAll(arena: Arena, fd: Int, bytes: ByteArray): String? {
    val state = arena.allocate(Libc.stateLayout)
    val buffer = arena.allocate(minOf(bytes.size, READ_SIZE).coerceAtLeast(1).toLong())
    var written = 0
    while (written < bytes.size) {
        val size = minOf(bytes.size - written, READ_SIZE)
        MemorySegment.copy(bytes, written, buffer, JAVA_BYTE, 0L, size)
        var offset = 0
        while (offset < size) {
            val unwritten = buffer.asSlice(offset.toLong())
            val count = Libc.write.invokeWithArguments(state, fd, unwritten, (size - offset).toLong()) as Long
            if (count >= 0) {
                offset += count.toInt()
                continue
            }
            when (val errno = Libc.errno(state)) {
                EINTR -> continue
                EPIPE -> return null
                else -> return reason(errno)
            }
        }
        written += size
    }
    return null
}

/** One of the child's output pipes, read non-blocking; its fd is -1 once it reached end of file. */
private class OutputPipe(var fd: Int, val stream: ChildStream) {
    val decoder = Utf8Stream()
    val isOpen: Boolean get() = fd >= 0

    fun close() = closeFd(fd.also { fd = -1 })
}

/**
 * Reads the child's output and waits for its exit in one `poll` loop, so whatever the child wrote before it ended
 * is read and posted before [ChildEvent.Exited]. The exited child is reaped only once its output closed and its
 * events were read past the end, or it was detached: until then its pid, which is its process group's id, cannot be
 * reused, so `stop` reaches the whole group and never someone else's.
 * Owns and closes every descriptor it is given.
 */
private class Reader(
    arena: Arena,
    private val pid: Int,
    stdout: Int,
    stderr: Int,
    private val wake: Int,
    private val events: FfmEvents,
) {
    private val pipes = listOf(OutputPipe(stdout, ChildStream.Stdout), OutputPipe(stderr, ChildStream.Stderr))
    private val state = arena.allocate(Libc.stateLayout)
    private val nativeBuffer = arena.allocate(READ_SIZE.toLong())
    private val buffer = ByteArray(READ_SIZE)
    private val watched = arena.allocate(POLLFD_BYTES * 4, 8)
    private val siginfo = arena.allocate(SIGINFO_BYTES, 8)
    private val count = arena.allocate(JAVA_INT)
    private val wakeValue = arena.allocate(JAVA_LONG)
    private var pidfd = -1
    private var exited = false
    private var held = false
    private var outputClosed = false

    /**
     * Reads to the end, then lets go of the child. A throw is a bug in kern, [FfmEvents.crashed]; the descriptors are
     * still closed and the child still reaped, a throw while letting go of it reported too.
     */
    fun run() {
        try {
            pidfd = pidfdOpen()
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
        } catch (bug: Throwable) {
            try {
                events.crashed(bug)
            } finally {
                // Nothing reads the pipes from here on: a child that writes gets SIGPIPE instead of blocking forever.
                pipes.forEach { it.close() }
                releaseAfter(bug)
            }
        }
    }

    /** [release] after [bug]; when that throws too, the throw is reported and the child reaped all the same. */
    private fun releaseAfter(bug: Throwable) {
        try {
            release()
        } catch (again: Throwable) {
            try {
                events.crashedAgain(bug, again)
            } finally {
                if (!exited || held) {
                    reapWhenEnded(pid, events, probed = false)
                    exited = true
                    held = false
                }
            }
        }
    }

    /** The child's pidfd, or -1 on a kernel without them, before Linux 5.3. */
    private fun pidfdOpen(): Int = (Libc.syscall.invokeWithArguments(state, SYS_PIDFD_OPEN, pid.toLong(), 0L) as Long)
        .toInt()

    private fun awaitReady() {
        val open = pipes.filter { it.isOpen }
        val watchesPidfd = !exited && pidfd >= 0
        val fds = open.map { it.fd } + listOfNotNull(pidfd.takeIf { watchesPidfd }) + wake
        fds.forEachIndexed { index, fd ->
            val offset = index * POLLFD_BYTES
            watched.set(JAVA_INT, offset, fd)
            watched.set(JAVA_SHORT, offset + 4, POLLIN)
            watched.set(JAVA_SHORT, offset + 6, 0.toShort())
        }

        if ((Libc.poll.invokeWithArguments(state, watched, fds.size.toLong(), -1) as Int) < 0) {
            val errno = Libc.errno(state)
            if (errno != EINTR) giveUp(reason(errno))
            return
        }
        if (ready(fds.lastIndex)) {
            val _ = Libc.read.invokeWithArguments(state, wake, wakeValue, 8L)
        }
        // The exit first: awaitExit reads what the pipes hold at that moment anyway, all of it before Exited.
        if (watchesPidfd && ready(open.size)) {
            awaitExit()
            return
        }
        for ((index, pipe) in open.withIndex()) {
            if (ready(index)) {
                val _ = readOnce(pipe)
            }
        }
    }

    private fun ready(index: Int): Boolean = watched.get(JAVA_SHORT, index * POLLFD_BYTES + 6).toInt() != 0

    /**
     * Waits for the exit without reaping the child, then posts what the pipes hold at that moment, then the exit. Only
     * that much: a background job that keeps writing must not hold the exit back.
     */
    private fun awaitExit() {
        val failure = waitFor(keep = true)
        exited = true
        held = failure == 0
        if (!held) {
            events.post(ChildEvent.Failed(ExecError.WaitFailed(pid, reason(failure))))
            return
        }
        val exitedNormally = siginfo.get(JAVA_INT, SIGINFO_CODE) == CLD_EXITED
        val status = siginfo.get(JAVA_INT, SIGINFO_STATUS)
        for (pipe in pipes) {
            var unread = if (pipe.isOpen) pending(pipe.fd) else 0
            while (unread > 0) {
                val read = readOnce(pipe, unread)
                if (read == 0) break
                unread -= read
            }
        }
        val code = exitCodeOf(status.takeIf { exitedNormally }, status.takeUnless { exitedNormally })
        events.post(ChildEvent.Exited(code))
    }

    /** Waits for the child to end, by pidfd when there is one, and reaps it unless [keep]. Returns 0 or errno. */
    private fun waitFor(keep: Boolean): Int {
        events.reached(FfmStep.Wait)
        val options = if (keep) WEXITED or WNOWAIT else WEXITED
        var byPidfd = pidfd >= 0
        while (true) {
            val (type, id) = if (byPidfd) P_PIDFD to pidfd else P_PID to pid
            if (Libc.waitid.invokeWithArguments(state, type, id, siginfo, options) as Int == 0) return 0
            val errno = Libc.errno(state)
            when {
                // Linux 5.3 opens pidfds but can wait on one only from 5.4 on.
                byPidfd && errno == EINVAL -> byPidfd = false
                errno != EINTR -> return errno
            }
        }
    }

    /** The bytes waiting in [fd], or -1. */
    private fun pending(fd: Int): Int =
        if (Libc.ioctl.invokeWithArguments(state, fd, FIONREAD, count) as Int == 0) count.get(JAVA_INT, 0) else -1

    /** Reads and posts up to [limit] bytes of [pipe], at most a buffer's worth. Returns how many; 0 for none. */
    private fun readOnce(pipe: OutputPipe, limit: Int = READ_SIZE): Int {
        events.reached(FfmStep.Read)
        val size = minOf(limit, READ_SIZE).toLong()
        while (true) {
            val read = (Libc.read.invokeWithArguments(state, pipe.fd, nativeBuffer, size) as Long).toInt()
            if (read > 0) {
                MemorySegment.copy(nativeBuffer, JAVA_BYTE, 0L, buffer, 0, read)
                post(pipe.decoder.feed(buffer, read), pipe.stream)
                return read
            }
            if (read == 0) {
                closePipe(pipe)
                return 0
            }
            val errno = Libc.errno(state)
            if (errno == EINTR) continue
            if (errno != EAGAIN) {
                events.post(ChildEvent.Failed(ExecError.ReadFailed(pipe.stream, reason(errno))))
                closePipe(pipe)
            }
            return 0
        }
    }

    private fun closePipe(pipe: OutputPipe) {
        post(pipe.decoder.end(), pipe.stream)
        pipe.close()
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

    /**
     * Stops `stop` from signalling, then reaps the child, which frees its pid for reuse. After a throw it first waits
     * for the child to end, unless that was done; called again, it reaps nothing twice.
     */
    private fun release() {
        try {
            if (!exited) {
                held = waitFor(keep = true) == 0
                exited = true
            }
            events.release()
            if (held) {
                // Fails only when something else in this process reaped the child first, which leaves nothing to do.
                val _ = waitFor(keep = false)
                held = false
                events.reached(FfmStep.Reaped)
            }
        } finally {
            closeFd(pidfd.also { pidfd = -1 })
        }
    }
}

/**
 * The events the reader thread posts and [next] takes, behind one lock. Also hands the outcome of the start to the
 * thread waiting in `start`, and sends [stop]'s signals under the same lock, so none is sent after the reader let go
 * of the child with [release]. A [ChildEvent.Failed] is the last event: the child is then read and dropped, and let go
 * of as after [ChildEvent.Exited]. A throw on the reader or stdin thread is a bug in kern, which [crashed] reports.
 */
private class FfmEvents(private val wake: Int, private val probe: ((FfmStep) -> Unit)?) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<ChildEvent>()
    private var start: Result<Int, ExecError.StartError>? = null
    private var ended = false
    private var failed = false
    private var detached = false
    private var released = false
    private var finished = false
    private var exhausted = false
    private var crash: Throwable? = null

    fun started(pid: Int) = locked { start = Ok(pid) }

    /** Answers `start` with [failure] unless it was answered already, and ends the events. */
    fun startFailed(failure: Err<ExecError.StartError>) = locked {
        if (start == null) start = failure
        endLocked()
    }

    /**
     * Hands [bug], a throw on the reader or stdin thread, to this thread's uncaught exception handler at once. The
     * first is also recorded, for [awaitStart] and [next] to rethrow unchanged, and nothing is queued from then on.
     */
    fun crashed(bug: Throwable) {
        locked { if (crash == null) crash = bug }
        report(bug)
    }

    /** Reports [again], thrown while cleaning up after [bug], and adds it to [bug] as suppressed for a later caller. */
    fun crashedAgain(bug: Throwable, again: Throwable) {
        bug.addSuppressed(again)
        if (again !== bug) report(again)
    }

    private fun report(bug: Throwable) {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler.uncaughtException(thread, bug)
    }

    /**
     * The outcome of the start, or the throw [crashed] recorded when the reader ended without one. A started child's
     * throw is left to [next], so the caller can still stop the child.
     */
    fun awaitStart(): Result<Int, ExecError.StartError> = locked {
        while (start == null && !ended) changed.awaitUninterruptibly()
        start ?: throw checkNotNull(crash)
    }

    /** Queues [event] unless the events already ended, by [end], a [ChildEvent.Failed], [detach] or a crash. */
    fun post(event: ChildEvent) = locked {
        if (ended || failed || detached || crash != null) return@locked
        queue.addLast(event)
        failed = event is ChildEvent.Failed
    }

    /** The reader is done: nothing more comes. */
    fun end() = locked { endLocked() }

    fun next(): ChildEvent? = locked {
        while (queue.isEmpty() && !finished && !ended && !failed && !detached && crash == null) {
            changed.awaitUninterruptibly()
        }
        if (detached) return@locked null
        crash?.let { throw it }
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
        changed.signalAll()
        while (!exhausted && !detached && crash == null) changed.awaitUninterruptibly()
    }

    /**
     * Sends [signal] to the process group [pid] leads. Sends nothing once the child was [release]d: its pid may then
     * lead another process's group.
     */
    fun stop(pid: Int, signal: Signal): EmptyResult<ExecError> = locked {
        if (released) return@locked Ok(Unit)
        reached(FfmStep.Stop)
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(Libc.stateLayout)
            val errno = if (Libc.kill.invokeWithArguments(state, -pid, signal.number) as Int == 0) {
                0
            } else {
                Libc.errno(state)
            }
            if (errno == 0 || errno == ESRCH) Ok(Unit) else Err(ExecError.StopFailed(pid, reason(errno)))
        }
    }

    fun reached(step: FfmStep) {
        probe?.invoke(step)
    }

    /** The child is about to be reaped: [stop] sends nothing from then on. */
    fun release() = locked { released = true }

    fun detach() = locked {
        detached = true
        queue.clear()
        if (!ended) wakeReader()
    }

    /** Makes the reader's poll return; it is woken only to drop what is queued, so a failed wake loses nothing. */
    private fun wakeReader() {
        reached(FfmStep.Wake)
        Arena.ofConfined().use { arena ->
            val one = arena.allocate(JAVA_LONG)
            one.set(JAVA_LONG, 0, 1L)
            val _ = Libc.write.invokeWithArguments(arena.allocate(Libc.stateLayout), wake, one, 8L)
        }
    }

    private fun endLocked() {
        if (ended) return
        ended = true
        closeFd(wake)
    }

    /** Runs [block] under the lock, then wakes every waiter, since most callers change what they wait for. */
    private inline fun <T> locked(block: () -> T): T = lock.withLock {
        try {
            block()
        } finally {
            changed.signalAll()
        }
    }
}

private fun closeFd(fd: Int) {
    if (fd >= 0) {
        val _ = Libc.close.invokeWithArguments(fd)
    }
}

private fun reason(errno: Int): String {
    val message = Libc.strerror.invokeWithArguments(errno) as MemorySegment
    return if (message == MemorySegment.NULL) "errno $errno" else message.reinterpret(Long.MAX_VALUE).getString(0)
}
