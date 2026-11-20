# exec: starting a child process and reading its output

Item 10 in `todo.md`. Status: design, not built.

**Amended 2026-11-19 (todo item 15, X1):** the JVM side needs Java 22 and starts only through FFM `posix_spawn`. The
Java 17 to 21 `ProcessBuilder` path of decisions 2 and 4, the multi-release jar and `SignalUnsupported` are gone; a JVM
that cannot use FFM (not Linux, not x86_64 or aarch64, native access denied) answers `ExecError.Unsupported` saying why
(todo item 16b; until then `StartFailed`). What this document says about Java 17 to 21 is history.

## Intent

A Kotlin program, a native Linux binary as much as a JVM one, should be able to run `ls -lah`, `xdgprint` or a
whole shell line and see what it writes as it writes it, how it ends, and stop it or detach from it. No kern module does
that today: `process` handles shutdown requests only, and `terminal` writes this process's own output.

The first user is kortex's runner (its PC-8 decisions): it runs the line a user typed through `/bin/sh -c`, shows
stdout and stderr live in arrival order, shows the exit code, and on Esc lets the command run on with its output
dropped. kortex has its own JVM `ProcessStarter` meanwhile and moves onto kern's once it is released. The owner also
wants a small CLI on top, `run -c "xdgprint"`, for trying changes by hand.

## Goals

- Start a command: argv, environment, working folder, stdin from nothing or from bytes.
- Its stdout and stderr as events tagged by stream, in the order they arrive, decoded as UTF-8.
- Its exit code, a death by signal N reported as 128+N, as `kern:process` and every shell report it.
- Every output the command wrote before it exited comes before its exit event.
- Output from jobs the command left in the background still comes, after the exit, until nothing writes any more.
- Stop it, with SIGTERM by default or a signal the caller names.
- Detach from it: it runs on, its output is read and dropped so it never blocks on a full pipe, and its exit is not
  reported. It can still be stopped.
- Every failure a typed `Result` (kern never throws), including a bad argument such as an empty argv.
- A fake that plays scripted output, exits and start failures, for code that starts commands.

## Non-goals

- A shell. kern starts an argv. A shell line is the caller's `listOf("/bin/sh", "-c", line)`.
- Sessions, process groups, `setsid`, `systemd-run` scopes. Those are argv in front of the command (kortex keeps its
  own); see the open question on stop below.
- A pseudo-terminal. The child sees pipes, so `ls` prints one name per line and programs drop their colours.
- Writing to the child's stdin after it started (interactive input).
- Raw bytes out. Output is text; binary output is decoded as UTF-8 with U+FFFD for bad bytes.
- mingw, macOS, iOS, Android, Node, Wasm in this round.

## Anti-goals

- Killing the child behind the caller's back: not when a collector is cancelled, not when a `Child` is dropped, not
  when this process exits. Stopping is only ever asked for by name.
- Losing output: an `Exited` that overtakes output the command wrote before it, or a dropped final chunk.
- A cap on how much output is read, or a ceiling the caller cannot raise.
- A child that hangs on a full pipe because nobody reads it.
- Zombies: a child that ended is always reaped, detached or not.

## Constraints

- Kotlin Multiplatform, Kotlin 2.4.21, the `kern-module` convention (explicit API, must-use return values, JVM
  bytecode 17, built on JDK 25).
- Targets this round: JVM and Linux native (linuxX64, linuxArm64 from the same source). Both are tested on this
  machine; linuxArm64 compiles only.
- The `kern-module` convention also adds Android, mingwX64 and the Apple targets to every module. `exec` has to
  either leave them out or build them; see open questions.
- kern conventions: `Result<S, E : ResultError>` from `kern:result`, one sealed error interface per module, README per
  module whose snippets are pinned by `usage`/`Readme*` tests, KDoc written for consumers.
- Kotlin/Native: code that runs between `fork` and `exec` must be async-signal-safe, so that part is C through a
  cinterop, as `kern:process` already does for its signal handler.

## Approaches

Three were considered.

1. **A new `exec` module family (chosen).** `exec` holds the command, the starter and a blocking `Child`; 
   `exec-coroutines` adds a `Flow` of the same events and a suspending wait; `exec-test` holds the fake. Mirrors
   `fs`/`fs-coroutines`/`fs-test` and `terminal`/`terminal-coroutines`/`terminal-test`. Its own target list, free of
   `process`'s web and androidNative targets, where no child process can be started.
2. Put the starter in `process` and the flow in a new `process-coroutines`. Rejected: `process` would have to answer
   "unsupported" on five targets, and shutdown handling and child processes share nothing.
3. Put everything in `process`. Rejected: everyone wanting shutdown requests would get coroutines too, plus the same
   unsupported targets.

The owner chose 1, named `exec`, `exec-coroutines`, `exec-test`.

## Design

### `exec` (package `com.fromwau.kern.exec`)

```kotlin
public data class Command(
    val argv: List<String>,                       // argv[0] without '/' is looked up in the child's PATH
    val environment: Environment = Environment.Inherit,
    val workingDirectory: Path? = null,           // null: this process's
    val stdin: Stdin = Stdin.Empty,
)

public sealed interface Environment {
    public data object Inherit : Environment
    public data class InheritWith(val set: Map<String, String>, val unset: Set<String> = emptySet()) : Environment
    public data class Exactly(val variables: Map<String, String>) : Environment
}

public sealed interface Stdin {
    public data object Empty : Stdin                      // /dev/null
    public class Bytes(public val bytes: ByteArray) : Stdin // written, then closed
}

public fun interface Starter {
    public fun start(command: Command): Result<Child, ExecError>
}

public object SystemStarter : Starter    // the real one; names are the builder's

public interface Child {
    public val pid: Int
    /** Blocks for the next event; null once none is left to come, or after detach. */
    public fun next(): ChildEvent?
    public fun stop(signal: Signal = Signal.Terminate): EmptyResult<ExecError>
    public fun detach()
}

public sealed interface ChildEvent {
    public data class Output(val text: String, val stream: Stream) : ChildEvent
    public data class Exited(val code: Int) : ChildEvent     // 128+N for signal N
    public data object OutputClosed : ChildEvent             // nothing writes stdout or stderr any more
    public data class Failed(val error: ExecError) : ChildEvent // reading or waiting failed; the last event
}

public enum class Stream { Stdout, Stderr }

public sealed interface ExecError : ResultError {
    // EmptyCommand, ProgramNotFound(program), NotExecutable(program), WorkingDirectoryMissing(path),
    // StartFailed(argv, reason), StopFailed(pid, reason); final set is the builder's, each typed, none a string-only
    // catch-all where the cause is known.
}
```

Behaviour:

- `start` returns once the program is running (exec succeeded) or with the error that stopped it. A missing program,
  a non-executable file and a missing working folder are told apart (native: `fork` + `exec` with a close-on-exec
  error pipe carrying `errno` back; JVM: the `IOException` from `ProcessBuilder.start` and checks before it).
- The child gets only fds 0 to 2: native marks every fd from 3 close-on-exec in the child (`close_range`, Linux 5.11,
  else `fcntl` up to the open-file limit); the JVM routes close them (see decision 2 for the glibc floor on 22+).
- Event order: `Output` events in arrival order across both streams; `Exited` after every byte the command wrote
  before it ended; `OutputClosed` once both pipes reached end of file. `Exited` and `OutputClosed` come once each, in
  either order (a background job keeps the pipes open past the exit). `next()` returns null after both.
- Ordering mechanism (no marker in the stream): when the exit is seen, whatever is already in the pipes is read and
  delivered before `Exited`. A dead process's writes are all in the pipe already. Native: one `poll` loop over both
  pipes and a `pidfd`; JVM: Java 22+ starts through FFM (decision 2), Java 17 to 21 see decision 4.
- UTF-8 decoding per stream; a character split across reads is held until complete, and each maximal subpart of an
  ill-formed sequence is one U+FFFD (the Unicode and WHATWG practice), the same on every platform however split.
- Chunk sizes are what the reads return; nothing is split into lines.
- `stop` sends the signal to the child's pid. A child that already ended is not an error. The exit then comes as
  usual (143 for SIGTERM).
- `detach` makes `next()` return null from then on; output is read and dropped on a thread of its own until both
  pipes close, and the child is reaped once it has ended and its output has closed. Its pid is held until then, so
  `stop` can never reach a process group that is no longer the child's. `stop` keeps working after `detach`, which
  means stop reading, not let go: a caller whose wait timed out can still kill the child.
- `Stdin.Bytes` is written from a thread of its own, so a child that writes before it reads cannot deadlock it; the
  pipe is closed after the last byte. A child that exits without reading drops the rest (EPIPE is not an error). Java
  17 to 21 tells EPIPE apart only by a message the system may translate, so there every stdin failure counts as EPIPE.
- When this process exits, nothing reads the pipes any more; a child that writes then gets SIGPIPE. Documented, not
  prevented.

### `exec-coroutines` (package `com.fromwau.kern.exec.coroutines`)

- `Child.events(): Flow<ChildEvent>`: the same events, read off the blocking `next()` on an IO dispatcher. Collect
  it once.
- `Child.awaitExit(): Int`: suspends until the exit code, keeping the output read and dropped meanwhile.
- Cancelling the collector does not stop the child; the child is detached so it never blocks (owner's anti-goal:
  no kill behind the caller's back).

### `exec-test` (package `com.fromwau.kern.exec.test`)

- `FakeStarter`: answers each `start` from a script the test gives per command (a list of events, or a start
  failure), records every `Command` it was asked to start, and fails the test-visible way (a typed error, never a
  throw) for a command it has no script for.
- The fake `Child` records `stop` and `detach` calls; a stopped fake child ends with the signal's 128+N.

### `exec-cli` (local only)

A separate Gradle build in `kern/exec-cli/`, listed in `.git/info/exclude`, so it is never committed, pushed or
released. Its own `settings.gradle.kts` includes the kern checkout (`..`) and the local klap checkout, as the
`xdgprint` test app did on 2026-11-19. A linuxX64 binary and a JVM run:

- `run -c LINE` runs `/bin/sh -c LINE`; `run PROG ARGS...` runs the argv.
- stdout as it comes, stderr in the error colour, then `exit N`.
- Flags for the rest of the API: `--cwd`, `--env K=V`, `--clear-env`, `--stdin TEXT`, `--stop-after DURATION`
  (with `--signal`), `--detach-after DURATION`, `--events` (print each event raw, for checking order).

## Testing

- `exec`: JVM and linuxX64 tests that start real `/bin/sh` children: output order across streams, `Exited` after the
  last pre-exit write (a loop of writes then exit, many rounds), a background job writing after `Exited`, exit codes
  0, 1, 255, signal deaths (SIGTERM 143, SIGKILL 137), stop of a running child and of an ended one, detach with a
  child that writes megabytes after it (must finish, not block), stdin bytes larger than a pipe buffer, every start
  error, UTF-8 split across reads, environment and working folder.
- `exec-coroutines`: the flow gives the same events; cancellation detaches; `awaitExit`.
- `exec-test`: the fake's own tests plus README snippets.
- The README snippets of all three pinned by tests, as in every kern module.

## Decisions on the open questions

1. ~~Targets.~~ **E3 decided: A.** JVM and Linux (linuxX64, linuxArm64) now, the rest later, each as a real
   implementation. `build-logic` gets `kern.androidTarget=false` and `kern.mingwTarget=false` beside
   `kern.appleTargets`; `exec`, `exec-coroutines` and `exec-test` set all three and add linuxArm64. No stub targets:
   a target either works or is not published. Android, macOS and mingw are `todo.md` item 11.
2. ~~Stop and process groups.~~ **E1 decided: B.** Every child starts in a process group of its own and `stop`
   signals the group, so `sh -c "a | b"` stops whole. Native: `setpgid` in the C start helper. JVM on Java 22+: the
   child is started through FFM (`posix_spawn` with `POSIX_SPAWN_SETPGROUP`) in a multi-release jar part, as
   `fs-coroutines` does for inotify. JVM on Java 17 to 21: `ProcessBuilder`, no group, `stop` signals the pid only;
   documented. The FFM path is taken only on Linux on x86_64 and aarch64, whose constants and struct layouts it
   uses; a JDK 22+ that refuses native access, or any other JVM, starts as Java 17 to 21 does. On 22+ the child
   gets only fds 0 to 2 where glibc has `posix_spawn_file_actions_addclosefrom_np` (2.34 on), so an fd this
   process opened without close-on-exec does not reach the child. After the leader has exited, `stop` reaches the
   group while the child's output is still open; once it has closed, `stop` is a success that signals nothing.
3. **E2 decided: A.** `Signal` is `Terminate`, `Kill`, `Interrupt`, `Hangup`, `Quit` and `Number(n)`. Native and
   Java 22+ send any of them with `kill(2)`. Java 17 to 21 sends `Terminate` (`destroy()`) and `Kill`
   (`destroyForcibly()`), by name or as `Number(15)` and `Number(9)`; any other is
   `ExecError.SignalUnsupported(signal)` and the child is left untouched.
4. **JVM on Java 17 to 21** (decided while building it). `ProcessBuilder` with its own streams, no JDK-internal access.
   The JDK reaps the child at its exit and ends both output streams after what the pipes held then, so these gaps beside
   E1 and E2: output a background job writes after the child exited is read only up to the job's first write, if at all,
   and the pid is not held. The child's `argv[0]` is the path the program was found at, not the name as given (a bare
   name only; a path is passed as given). Two reader threads read to the end of their streams; the one that ends last
   posts `OutputClosed`, then `Exited`, so `Exited` comes after all pre-exit output by construction, with no snapshot. A
   background job that holds the output open without writing holds the end back until it writes or closes it, and
   `Exited` with it: late, never ahead of output. `stop` signals through the process handle, since `Process.destroy`
   would also close the streams.

## Calls made after the builder check (the author's, open to correction)

- **A failure after start is an event.** A failed read, poll, wait or stdin write (other than EPIPE) is
  `ChildEvent.Failed(error)`, the last event `next()` gives; the child is not stopped. Chosen over
  `next(): Result<...>` so the `Flow` stays a flow of events and a fake scripts a failure like any other event.
- **`awaitExit()` answers `Result<Int, ExecError>`**: the code, a `Failed`'s error, or `ExecError.Detached` for a child
  detached before its exit came.
- **Output is read from the moment the child starts**, by the module itself, not by `next()`. What `next()` has not
  taken yet is buffered without a cap (the owner's no-ceiling rule; kortex keeps all output anyway, PC-8d). A `Child`
  dropped without `next()` or `detach()` is still read and reaped. `detach()` and `stop()` from another thread wake a
  `next()` that is blocked (native: a wake-up fd in the `poll` set).
- **The fake never meets an unscripted command.** `FakeStarter` takes a function from `Command` to its scripted run
  (events, or a start failure), so `exec` needs no error case for fakes and a test cannot hide a missing script behind
  `ProgramNotFound`. It still records every command.
- **Cancelling `awaitExit()` detaches, as cancelling `events()` does.** One rule: whatever stops reading a child lets
  it run on, read and dropped.

## Left to the builder

- Exact names of the starter object, error cases and signal type, within the shapes above.
- The C helper's shape for fork/exec on native, and `pidfd` vs `SIGCHLD` for the exit (the machine runs Linux 7.x;
  `pidfd_open` is in Linux 5.3).
- Thread layout on the JVM, as long as the ordering guarantee holds.
- Buffer sizes.
