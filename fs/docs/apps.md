# Whole apps

The other pages show one call at a time. A real app needs them together, in the right order. This page shows three
programs from `main` to exit code: a command-line tool, a daemon and a site builder.

Copy the one closest to your app and change what it does. The order of the calls is the part worth keeping.

Part of [`fs`](../README.md).

## Rules

- Everything but `main` is common code. A program writes each problem through the `err` it is handed, and answers
  its exit code.
- So it runs the same on the JVM and on native, and a test can call it with a list for `err`.
- `main` is the one platform line. `exitProcess` is in `kotlin.system` on the JVM and native, not in `commonMain`.
- Put `main` in `jvmMain` or `nativeMain`. Or end the process through the `expect fun exit(code: Int): Nothing` the
  [`terminal` README](../../terminal/README.md) shows.
- `defaultTerminal().err(...)` from kern's [`terminal`](../../terminal/README.md) writes to stderr on every target.
  `System.err` exists on the JVM only.
- Resolve `AppDirs` once and pass it down, since `forApp` draws a new run id for `temp` on every call.
- Take `singleInstance` before writing anything the app owns. Clear what crashed runs left before the work.
- `clearStaleTempRuns` is safe before `singleInstance` or after it, as [the run temp's rules](run-temp.md#rules) say,
  so the tool clears first, and the site builder clears once it holds the lock.

## Tasks

### A command-line tool

This tool counts its runs in a config file. It reads the config, writes it back, and keeps a note and a session
token in its other folders.

```kotlin
/** Runs the tool in the folders this process's environment gives, and exits with its code. */
fun main() {
    exitProcess(runTool(BaseDirs.current()) { defaultTerminal().err("tool: $it\n") })
}

/** Runs the tool once in the folders [found] holds, writing each problem through [err], and answers its exit code. */
fun runTool(
    found: Result<BaseDirs, DirsError>,
    err: (String) -> Unit,
): Int {
    val base = found.getOrElse { failure ->
        err("no folders: $failure")
        return 2
    }
    val refused = base.ignored.firstOrNull { it.name != "XDG_RUNTIME_DIR" }?.toError()
    if (refused != null) {
        err("$refused; fix the variable and run again")
        return 2
    }
    val dirs = base.forApp("tool").getOrElse { failure ->
        err("no folders: $failure")
        return 2
    }
    dirs.clearStaleTempRuns().onError { err("old runs not cleared: $it") }
    val done: EmptyResult<FileError> = result {
        dirs.singleInstance("tool").bind().use { dirs.withRunTemp { temp -> countRun(dirs, temp).bind() }.bind() }
    }
    done.onError { failure ->
        err("$failure")
        return exitCode(failure)
    }
    return 0
}

/** Counts this run in the config, notes it in the data folder and keeps a session token in state. */
fun countRun(
    dirs: AppDirs,
    temp: Writer,
): EmptyResult<FileError> = result {
    val config = dirs.config / "tool.toml"
    val text = config.readText(maxBytes = 1_048_576, trusted = true).orIfNotFound { "runs = 0\n" }.bind()
    val runs = text.substringAfter("runs = ").substringBefore('\n').toIntOrNull() ?: 0
    writeConfig(dirs, config, "runs = ${runs + 1}\n").bind()
    temp.writeText("last-run.txt", "run ${runs + 1}\n").bind()
    dirs.data.writer().bind().copyFrom(temp.root / "last-run.txt", "last-run.txt").bind()
    dirs.state.writer().bind().privateWriter("secrets").bind().writeText("session", newSessionToken()).bind()
}

/**
 * Writes [text] to [config] for its owner alone. A backup a killed in-place write of it left is put back instead, and
 * the run ends as that [FileError.PendingBackup], since the text it read may be half of that write.
 */
fun writeConfig(
    dirs: AppDirs,
    config: Path,
    text: String,
): EmptyResult<FileError> {
    val failure = config.writeText(text, ownerOnly = true).errorOrNull() ?: return Ok(Unit)
    if (failure !is FileError.PendingBackup) return Err(failure)
    val folder = dirs.config.writer(create = false).getOrElse { return Err(it) }
    folder.restore(failure.backup, throughLinks = true).onError { return Err(it) }
    return Err(failure)
}

/** The exit code for [failure]: a few by kind, the rest `1`. */
fun exitCode(failure: FileError): Int = when (failure) {
    is FileError.LockBusy -> 3
    is FileError.Refused, is FileError.RunTempClaimed -> 4
    is FileError.WriteFailed -> if (failure.cause == IoCause.NoSpace) 5 else 1
    is FileError.PendingBackup -> 6
    else -> 1
}
```

- `BaseDirs.current()` with the check on `ignored` refuses a blank or relative variable, such as
  `XDG_STATE_HOME=state`. The folders would be resolved without it, so the tool never puts its state in the default
  folder by mistake.
- The check lets through whatever is wrong with `XDG_RUNTIME_DIR`, which this tool never uses, as
  [Refuse a relative variable](../../dirs/README.md#refuse-a-relative-variable) shows.
- Old runs that cannot be cleared do not stop the tool. Every error after that goes to `err` and becomes an exit
  code.
- The config is read with `trusted = true`, so one others may change is `Refused`.
- It is written back `ownerOnly`: `600`, and each folder made for it `700`, so a umask such as `002` never turns it
  into one `trusted` refuses.
- The report is written in the run temp and copied into `data` with `copyFrom`. The run temp is often a `tmpfs`, and a
  move across two file systems is `WriteFailed`.
- `newSessionToken()` is yours, as `render` and `serveNext` are below. A random `Uuid` as text will do.
- The session token goes in `state/secrets`, a private child writer: `700`, its file `600`.
- `singleInstance` holds the lock in `state`. A `state` it makes is `700`, and one already there keeps its
  permissions.
- Builds with `kern:fs` and `kern:terminal`. Imports from `com.fromwau.kern.fs`, `.dirs`, `.result` and `.terminal`,
  `kotlinx.io.files.Path` and `kotlin.system.exitProcess`.

#### Details

- A config linked into a dotfiles folder is read and written through the link.
- A backup a killed in-place write left then sits beside the link's target, so the writer on `config` restores it
  with `throughLinks = true`.

### A daemon

This daemon runs until it has served its last request. It recovers from a crash, keeps a log, and reloads its config
when the file changes.

```kotlin
/** Runs the daemon in the folders this process's environment gives, and exits with its code. */
fun main() {
    exitProcess(runBlocking { runDaemon(BaseDirs.current()) { defaultTerminal().err("daemon: $it\n") } })
}

/** Runs the daemon in the folders [found] holds until it is done, writing each problem through [err]. */
suspend fun runDaemon(
    found: Result<BaseDirs, DirsError>,
    err: (String) -> Unit,
): Int {
    val dirs = found.flatMap { it.forApp("daemon") }.getOrElse { failure ->
        err("no folders: $failure")
        return 2
    }
    val served: EmptyResult<FileError> = result {
        dirs.singleInstance("daemon").bind().use { instance ->
            val state = dirs.state.writer().bind()
            recover(state, mine = setOf(dirs.state / "db" / "index.json"), err)
            val marker = dirs.runtime?.privateWriter()?.bind()
            marker?.writeText("daemon.pid", "${currentProcessId()}\n")?.bind()
            val db = state.privateWriter("db").bind()
            state.openAppend("daemon.log", Reopen.WhenMoved(), replaceLink = true).bind().use { log ->
                serveWithConfig(instance, dirs.config / "daemon.toml", db, log, err).bind()
            }
            marker?.delete("daemon.pid")?.bind()
        }
    }
    served.onError { failure ->
        err("$failure")
        return when (failure) {
            is FileError.LockBusy -> 3
            is FileError.LockLost -> 4
            else -> 1
        }
    }
    return 0
}

/** Puts back what killed in-place writes of [mine] left in [state], and removes every other leftover there. */
fun recover(
    state: Writer,
    mine: Set<Path>,
    err: (String) -> Unit,
) {
    val scan = state.leftovers(depth = Int.MAX_VALUE).getOrElse { failure ->
        err("leftovers not checked: $failure")
        return
    }
    scan.failed.forEach { err("not checked: ${it.error}") }
    val realMine = mine.map { it.real() }.toSet()
    val oldest = scan.oldestBackups().values.filter { it.file.real() in realMine }
    for (backup in oldest) state.restore(backup).onError { err("not restored: $it") }
    // Only an in-place write leaves a Backup, so one beside a file outside `mine` is not the daemon's.
    for (leftover in scan.found - oldest.toSet()) {
        state.discard(leftover).onError { if (it !is FileError.NotFound) err("not removed: $it") }
    }
}

/** This path with every link on the way resolved, or as it is when that fails. */
fun Path.real(): Path = realPath().getOrElse { this }

/**
 * Serves with the config at [config] loaded, loading it again each time its text changes, until [serveNext] answers
 * that no request is left or [instance] is lost.
 */
suspend fun serveWithConfig(
    instance: InstanceLock,
    config: Path,
    db: Writer,
    log: AppendFile,
    err: (String) -> Unit,
): EmptyResult<FileError> = coroutineScope {
    val settings = MutableStateFlow<String?>(null)
    val watch = launch {
        config.textChanges(maxBytes = 1_048_576, trusted = true).collect { text ->
            text.onSuccess { settings.value = it }.onError { err("config not loaded: $it") }
        }
    }
    val served = result {
        while (serveNext(settings, db, log)) instance.check().bind()
    }
    watch.cancel()
    served
}
```

- `singleInstance` holds the lock for the whole run. A `state` it makes is `700`, and one already there keeps its
  permissions.
- The log and the recovery go through a shared writer on `state`. The log is `644`, or narrower as the umask says. A
  private writer reads only files nobody else may read.
- What only the daemon may read goes in `db`, a private child writer: `700`, its files `600`.
- The recovery runs before the daemon writes anything of its own. It restores the oldest backup of each file in
  `mine`, the files the daemon writes in place.
- It discards every other leftover: later backups, a backup beside a file written by rename, the temps and what
  killed folder builds left.
- The pid marker goes in `runtime` through `privateWriter()`, so it is `600`. It is removed on a clean exit, so
  whatever reads it as proof the daemon runs is not misled by a finished one.
- `runtime` is null without `$XDG_RUNTIME_DIR`, and then there is no marker.
- The log follows a rotation. `Reopen.WhenMoved()` opens `daemon.log` again once a tool such as `logrotate` moved it.
- `replaceLink = true` puts a new file in place of a link someone left at the log's name, and never writes through
  it.
- `textChanges(trusted = true)` from
  [`fs-coroutines`](../../fs-coroutines/README.md#reload-a-file-when-its-text-changes) loads the config at the
  start, and again on each change of its text.
- `serveNext` is yours. It serves one request, reading `settings.value`, and answers whether another comes.
  `settings.value` is null until the config was loaded.
- Before each next request, `instance.check()` answers `LockLost` once the lock file was removed, and the daemon
  stops. A new start may be running beside it by then.
- Builds with `kern:fs`, `kern:fs-coroutines`, `kern:terminal` and `kotlinx-coroutines-core`. Imports from
  `com.fromwau.kern.fs`, `.fs.coroutines`, `.dirs`, `.result` and `.terminal`, `kotlinx.coroutines`,
  `kotlinx.coroutines.flow`, `kotlinx.io.files.Path` and `kotlin.system.exitProcess`.

#### Details

- The daemon's writers never write through a link, so no backup of `mine` sits beside a link's target.
- A leftover gone with a folder discarded before it is `NotFound`, and the recovery does not report it.
- `singleInstance`'s own `daemon.pid` in `state` stays after the daemon exits, so it is no proof the daemon runs. The
  live holder is the one a second start's `LockBusy.holderPid` names.
- `textChanges` also loads the config once a `chmod` of the config or its folder fixed one it refused.
- After a `LockLost`, the marker stays for the new start to replace.
- `systemctl stop` sends `SIGTERM`, which kills the process. The marker stays until the next start writes it again.
- To stop cleanly instead, let `stopOnShutdownRequest()` from kern's [`process`](../../process/README.md) set a flag
  `serveNext` checks, as [Clear the folder on Ctrl-C](run-temp.md#clear-the-folder-on-ctrl-c) does.

### A site builder

This builder turns a folder of pages into a site folder. It renders only the pages that changed, and removes pages
the source no longer has.

```kotlin
/** Builds the site from the pages in the first argument into the folder in the second, and exits with its code. */
fun main(args: Array<String>) {
    if (args.size != 2) {
        defaultTerminal().err("usage: site <pages> <out>\n")
        exitProcess(2)
    }
    val code = buildSite(BaseDirs.current(), Path(args[0]), Path(args[1])) { defaultTerminal().err("site: $it\n") }
    exitProcess(code)
}

/** Builds [pages] into [out] once no other build of [out] runs, writing each problem through [err]. */
fun buildSite(
    found: Result<BaseDirs, DirsError>,
    pages: Path,
    out: Path,
    err: (String) -> Unit,
): Int {
    val dirs = found.flatMap { it.forApp("site") }.getOrElse { failure ->
        err("no folders: $failure")
        return 2
    }
    val built: Result<MirrorReport, FileError> = result {
        dirs.singleInstance(out).bind().use {
            dirs.clearStaleTempRuns().onError { err("old runs not cleared: $it") }
            dirs.withRunTemp { temp ->
                pages.mirrorTo(
                    out.writer().bind(),
                    isCurrent = { source, existing -> existing.modifiedAt == source.modifiedAt },
                    copy = { source, site, name ->
                        render(source.path, temp, name).flatMap {
                            site.copyFrom(temp.root / name, name, modifiedAt = source.modifiedAt)
                        }.map { }
                    },
                ).bind()
            }.bind()
        }
    }
    val report = built.getOrElse { failure ->
        err("$failure")
        return if (failure is FileError.LockBusy) 3 else 1
    }
    val unclean = report.unclean.filterNot { reason ->
        reason is MirrorReport.Unclean.Kept && reason.removal.why is RemovalReason.AtPassedOver
    }
    unclean.forEach { err("not published: $it") }
    return if (unclean.isEmpty()) 0 else 1
}
```

- `singleInstance(out)` keys the build by its output folder, so two builds of one site keep each other out however
  each spells the folder. Builds of two sites run side by side.
- `render` is yours. It writes the page at `source.path` into the run temp as `name`.
- `copy` then copies the page into `out` with its source's modification time. `isCurrent` takes a page with that time
  as current and leaves it alone, since a rendered page rarely matches its source's size.
- The run temp is cleared once the block returns.
- `mirrorTo` walks `pages`, subfolders included. It makes `out` hold a page for each file, and removes what the site
  no longer has.
- `main` exits `2` with a usage line unless it is given two arguments.
- Builds with `kern:fs` and `kern:terminal`. Imports from `com.fromwau.kern.fs`, `.dirs`, `.result` and `.terminal`,
  `kotlinx.io.files.Path` and `kotlin.system.exitProcess`.

#### Details

- A folder of `pages` that cannot be read keeps its old pages in `out`, and is a `Failed` in `unclean`.
- The check on `unclean` is a stricter form of the one in
  [Walking](walking.md#back-up-a-folder-with-its-links-as-links).
- Both accept what the default `approveRemovals` kept at the place of an entry the walk passed over. This one also
  fails on a link not made because it leads out of `pages`. That one accepts such a link.

## Reference

Each program answers one of these exit codes.

| exit code | command-line tool | daemon | site builder |
|---|---|---|---|
| 0 | done | done | built, with nothing but accepted entries in `unclean` |
| 1 | any other `FileError` | any other `FileError` | any other `FileError`, or `unclean` holds more |
| 2 | folders not resolved, or a variable to fix | folders not resolved | folders not resolved, or not two arguments |
| 3 | `LockBusy`: another run holds the lock | `LockBusy` | `LockBusy`: another build of `out` runs |
| 4 | `Refused` or `RunTempClaimed`: a file or folder others may change, or the run temp taken | `LockLost`: lock file removed | |
| 5 | `WriteFailed` with `IoCause.NoSpace` | | |
| 6 | `PendingBackup`: the config was put back from a killed write; run again | | |

- Each program writes the error behind a code through `err` before answering it.
- `kotlinx.coroutines`' `runBlocking` drives the daemon from `main`. The tool and the site builder do not suspend.
