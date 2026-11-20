package com.fromwau.kern.exec

import kotlinx.io.files.Path

/**
 * A program to start and how to start it.
 *
 * ```kotlin
 * val command = Command(
 *     argv = listOf("git", "log", "--oneline"),
 *     workingDirectory = Path("/work/repo"),
 * )
 * ```
 *
 * @property argv the program followed by its arguments, passed to it exactly as given: no shell reads them, so
 * spaces, quotes, empty strings and `*` mean nothing special. A name without a `/` is looked up in the `PATH` of
 * the child's [environment]. An empty list starts nothing and is [ExecError.EmptyCommand]; an argument holding a NUL
 * character is [ExecError.InvalidCommand].
 * @property environment the variables the child sees. A name that is empty or holds `=`, or a name or value holding a
 * NUL character, is [ExecError.InvalidCommand].
 * @property workingDirectory the folder the child starts in, or this process's own when null. A path holding a NUL
 * character is [ExecError.InvalidCommand].
 * @property stdin what the child reads from its standard input.
 * @property stdout where the child's standard output goes; read through [Child.next] only when it is [Output.Pipe].
 * @property stderr where the child's standard error goes, as for [stdout], or [Output.ToStdout] for wherever stdout
 * goes.
 * @property newSession whether the child leads a new session of its own, with no controlling terminal, instead of
 * joining this process's: then the hangup sent when this process's terminal closes never reaches it. Either way the
 * child runs in a process group of its own, which [Child.stop] signals.
 */
public data class Command(
    val argv: List<String>,
    val environment: Environment = Environment.Inherit,
    val workingDirectory: Path? = null,
    val stdin: Stdin = Stdin.Empty,
    val stdout: Output = Output.Pipe,
    val stderr: Output = Output.Pipe,
    val newSession: Boolean = false,
)

/** The variables a child starts with. */
public sealed interface Environment {
    /** This process's own variables, unchanged: byte for byte, a value that is not UTF-8 too. */
    public data object Inherit : Environment

    /**
     * This process's variables with [set] added or replaced and then [unset] removed.
     *
     * @property set variables to add, replacing a variable of the same name.
     * @property unset names of variables to remove; a name in both [set] and [unset] is removed.
     */
    public data class InheritWith(
        val set: Map<String, String>,
        val unset: Set<String> = emptySet(),
    ) : Environment

    /**
     * Only [variables]: nothing is inherited, `PATH` included, so a bare program name is
     * [ExecError.ProgramNotFound] unless [variables] holds a `PATH` that finds it.
     */
    public data class Exactly(val variables: Map<String, String>) : Environment
}

/** What a child reads from its standard input. */
public sealed interface Stdin {
    /** Nothing: the child sees the end of its input at once. */
    public data object Empty : Stdin

    /**
     * Exactly [bytes], then the end of input. A child that exits without reading them all is not an error.
     *
     * @property bytes the content to write; not copied, so leave it unchanged until the child has exited.
     */
    public class Bytes(public val bytes: ByteArray) : Stdin
}

/**
 * Where an output stream of a child goes. Only [Pipe] is read through [Child.next]; with neither stream piped,
 * [ChildEvent.OutputClosed] comes at once and [ChildEvent.Exited] when the child ends.
 */
public sealed interface Output {
    /** To this process, which reads it through [Child.next]. */
    public data object Pipe : Output

    /** Nowhere: the child writes to `/dev/null`, so a child that outlives this process never dies of SIGPIPE. */
    public data object Discard : Output

    /** Wherever this process's own stream of that name goes, such as its terminal. */
    public data object Inherit : Output

    /**
     * Into the file at [path], created when missing, with the mode a shell's `>` gives it.
     *
     * @property path the file; a relative one is taken from the command's working folder, as a shell's
     * `cd dir && cmd > log` does.
     * @property append whether to add to the end of an existing file instead of emptying it first.
     */
    public data class File(val path: Path, val append: Boolean = false) : Output

    /** For stderr only: wherever stdout goes, as a shell's `2>&1`. As stdout it is [ExecError.InvalidCommand]. */
    public data object ToStdout : Output
}
