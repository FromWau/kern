package com.fromwau.kern.exec

import com.fromwau.kern.result.ResultError
import kotlinx.io.files.Path
import kotlin.time.Duration

/** Why a child could not be started, signalled or read. */
public sealed interface ExecError : ResultError {
    override val errorType: String get() = "Exec.${this::class.simpleName}"

    /** Why [Starter.start] started nothing; every other [ExecError] comes from a child that started. */
    public sealed interface StartError : ExecError

    /** The command's argv is empty. */
    public data object EmptyCommand : StartError

    /**
     * The command holds what no process can be given, such as a NUL character in an argument or an environment
     * variable name holding `=`. Nothing was started.
     *
     * @property argv the command's argv.
     * @property problem what is wrong with it.
     */
    public data class InvalidCommand(val argv: List<String>, val problem: String) : StartError

    /**
     * No program of this name was found.
     *
     * @property program the name, as given in argv.
     */
    public data class ProgramNotFound(val program: String) : StartError

    /**
     * The program exists but may not be run.
     *
     * @property program the name, as given in argv.
     */
    public data class NotExecutable(val program: String) : StartError

    /**
     * The command's working directory does not exist or is not a folder.
     *
     * @property path the folder asked for.
     */
    public data class WorkingDirectoryMissing(val path: Path) : StartError

    /**
     * The file [Output.File] names for [stream] could not be opened, so nothing was started.
     *
     * @property path the file, a relative one taken from the command's working folder.
     * @property stream the stream that was to go there.
     * @property reason the system's message.
     */
    public data class OutputFileFailed(val path: Path, val stream: ChildStream, val reason: String) : StartError

    /**
     * The program exists and may be run, but what it needs to run is missing: the interpreter its `#!` line names, or
     * the dynamic loader a binary built for another system asks for.
     *
     * @property program the name, as given in argv.
     */
    public data class InterpreterMissing(val program: String) : StartError

    /**
     * This platform cannot start children: a JVM other than one on x86_64 or aarch64 Linux, a JVM that denies this
     * library native access, or a C library too old for what the command asks.
     *
     * @property reason what is missing and how to get it, where there is a way.
     */
    public data class Unsupported(val reason: String) : StartError

    /**
     * The operating system refused to start the child, such as when a process or memory limit was reached.
     *
     * @property argv the command's argv.
     * @property reason the system's message.
     */
    public data class StartFailed(val argv: List<String>, val reason: String) : StartError

    /**
     * Sending a signal failed.
     *
     * @property pid the child.
     * @property reason the system's message.
     */
    public data class StopFailed(val pid: Int, val reason: String) : ExecError

    /**
     * Reading a stream of the child failed.
     *
     * @property stream the stream.
     * @property reason the system's message.
     */
    public data class ReadFailed(val stream: ChildStream, val reason: String) : ExecError

    /**
     * Waiting for the child to end failed.
     *
     * @property pid the child.
     * @property reason the system's message.
     */
    public data class WaitFailed(val pid: Int, val reason: String) : ExecError

    /**
     * Writing the command's stdin failed for a reason other than the child closing it.
     *
     * @property reason the system's message.
     */
    public data class StdinFailed(val reason: String) : ExecError

    /**
     * What `run` from `kern:exec-coroutines` answers when the command was still running at its timeout. The command and
     * its process group were stopped.
     *
     * @property after the timeout.
     * @property stdout what it wrote to stdout before the timeout.
     * @property stderr what it wrote to stderr before the timeout.
     */
    public data class TimedOut(val after: Duration, val stdout: String, val stderr: String) : ExecError

    /**
     * What `awaitExit` from `kern:exec-coroutines` returns when no exit is left to read: the child was detached with
     * [Child.detach], or its events were read to the end.
     */
    public data object Detached : ExecError
}
