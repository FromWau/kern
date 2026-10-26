# fs-test

A test of file code needs a folder of its own and some odd things in it: a symlink, a hard link, a file only its owner
may read, a FIFO. It may also need what a killed write leaves behind. This module makes all of that for tests of
[`kern:fs`](../fs/README.md) code.

- `scratchDir()` makes a scratch folder of the test's own.
- Small helpers make the symlinks, hard links, permission bits and FIFOs a test sets up. Each fails with `fs`'s typed
  `FileError` rather than throwing.
- `leaveLeftover` makes the files a killed write leaves behind.

**Targets:** the same as [`kern:fs`](../fs/README.md).

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:fs-test:$kernVersion")
}
```

- KMP consumers put it in `commonTest`.
- `kern:fs` and `kern:result-test` come along as `api`. `result-test` gives you `assertSuccess()`, as in the examples
  below.
- Everything here is in the package `com.fromwau.kern.fs.test`.
- The assertions of `result-test`, such as `assertSuccess()`, are in `com.fromwau.kern.result`, so
  `import com.fromwau.kern.result.*` brings them in.
- Needs what [every kern module needs](../README.md#requirements).

## Rules

- `scratchDir()` makes a new, empty folder, `kern-scratch-<uuid>`, directly under the temp folder. It answers its
  `Path`.
- The scratch folder is `755` whatever the umask.
- Nothing removes it, so a test deletes it when it is done.
- The temp folder is `$TMPDIR` whenever that is an absolute path. This holds on every target, the JVM included.
- Otherwise it is the platform's own: `java.io.tmpdir` on the JVM and Android, and the system's on native. On Linux
  that is `/tmp` when nothing names one.
- Links in the temp folder's path are resolved, whichever it is. The scratch folder's path is its real one, the same
  `realPath()` gives.
- A scratch folder or leftover that cannot be made throws `IllegalStateException`, since the test cannot go on.
- Every other helper answers an `EmptyResult<FileError>`. It checks its paths as every `fs` call does.
- It fails with the cause an `fs` call would give, such as `ReadOnly` or `Loop`.

## Tasks

### Use a scratch folder in a test

```kotlin
import com.fromwau.kern.fs.*
import com.fromwau.kern.fs.test.*
import com.fromwau.kern.result.*
import kotlin.test.*

class ConfigTest {
    private val dir = scratchDir()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    @Test
    fun `a config only its owner may read is loaded`() {
        val config = dir / "app.toml"
        config.writeText("a = 1").assertSuccess()
        config.chmod(0b110_000_000).assertSuccess()

        assertEquals(Ok("a = 1"), config.readText())
    }
}
```

If a test sets a folder in it to `000` with `chmod`, give that folder its mode back before `deleteRecursively()`, or
it cannot be removed.

### Rerun tests when `TMPDIR` changes

Gradle does not count `TMPDIR` as an input of a test task, so changing it alone leaves the task up to date. Run with
`--rerun-tasks`, or declare it an input in the module's `build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

// The shell's TMPDIR, or none, which leaves each test on its platform's own temp folder.
val tmpDir: String = providers.environmentVariable("TMPDIR").orElse("").get()

tasks.withType<Test>().configureEach {
    environment("TMPDIR", tmpDir)
    inputs.property("TMPDIR", tmpDir)
}
tasks.withType<KotlinNativeTest>().configureEach {
    environment("TMPDIR", tmpDir)
}
```

- `KotlinNativeTest` counts a variable set with `environment` as an input itself.
- The JVM's `Test` needs it declared.
- With the build cache on, a cached result is the one for this `TMPDIR`. Pass `--rerun-tasks` to see the tests run.

### Make links, modes, times and FIFOs

| call | what it makes |
|---|---|
| `link.symlinkTo(target)` | a symlink holding `target` as given, and the missing folders on the way |
| `link.hardLinkTo(target)` | a second name for the file at `target`, and the missing folders on the way |
| `path.chmod(bits)` | the permission bits of what `path` reaches, `0b110_000_000` for `600` |
| `fifo.mkfifo()` | a FIFO, a named pipe, and the missing folders on the way |
| `path.setModifiedAt(at)` | when what `path` reaches was last written |

- `symlinkTo` makes the link as `createSymlink` from `fs` makes it.
- `hardLinkTo` makes the missing folders as `symlinkTo` makes them.
- `chmod` sets setuid, setgid and sticky too.
- `mkfifo` makes the FIFO with what the umask leaves of `666`.
- `setModifiedAt` sets the time as `info().modifiedAt` reads it back. Use it, for example, to make a copy look older
  than its source.

Assert each with `assertSuccess()` from `kern:result-test`.

```kotlin
val fifo = dir / "pipe"
fifo.mkfifo().assertSuccess()

assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.readText())
```

#### What each one fails with

Every helper that takes a path checks it as `fs` checks it. An empty path is `NotFound`. A path holding a NUL is
`InvalidName`. A name over 255 bytes is `NameTooLong`.

- `symlinkTo` fails with what `createSymlink` answers:
  - `AlreadyExists` for anything at `link`
  - the path checks every `fs` call makes
  - `NotFound` naming `link`'s folder when a file stands on the way
  - else `WriteFailed`.
- `hardLinkTo`:
  - `AlreadyExists` for anything at `link`
  - the path checks every `fs` call makes, such as `Inaccessible(Loop)` for a folder on the way that is a symlink
    loop
  - `NotFound` naming `link`'s folder when a file stands on the way
  - else `WriteFailed`, with the cause `NotFound` for a missing `target`.
- `chmod`: `NotFound` for nothing at `path`, else `WriteFailed`.
- `mkfifo`: `AlreadyExists` for anything at `fifo`, `NotFound` naming its folder when a file stands on the way, else
  `WriteFailed`.
- `setModifiedAt`: `NotFound` for nothing at `path`, else `WriteFailed`.

### Leave a leftover

Startup and recovery code looks for what a killed write left. To test it, you need such a leftover without killing a
write. `file.leaveLeftover(kind)` makes one.

- It leaves a real leftover of `kind` beside `file`. It is named as `fs`'s own writes name it, with a new write id.
- It answers the leftover's path.
- `leftovers()` or `leftoversIn()` then find it as that kind.
- `file` need not exist. Its folder is created when missing.
- At a `file` that is a symlink, it follows the link as a real write does. A `Temp`, `UnnamedTemp`, `Backup` or
  `PartialBackup` goes beside the file the link leads to. A `StagedLink` or `StagedFolder` goes beside the link.

| `LeftoverKind` | what it leaves | found as |
|---|---|---|
| `Temp` | `.<name>.<id>.tmp`, a file holding `content` | `Leftover.Temp` |
| `UnnamedTemp` | `.<id>.tmp`, a file holding `content` | `Leftover.UnnamedTemp` |
| `Backup` | `.<name>.<id>.bak`, a file holding `content` | `Leftover.Backup` |
| `PartialBackup` | `.<name>.<id>.bak.part`, a file holding `content` | `Leftover.PartialBackup` |
| `StagedFolder` | `.<name>.<id>.dir`, an empty folder | `Leftover.StagedFolder` |
| `StagedLink` | `.<name>.<id>.link`, a symlink | `Leftover.StagedLink` |

The `StagedLink` holds `content` as its target, or the file's name when `content` is empty.

```kotlin
val config = dir / "app.toml"
config.writeText("port = 2").assertSuccess()
val backup = config.leaveLeftover(LeftoverKind.Backup, content = "port = 1")

val found = dir.leftoversIn().assertSuccess().found.single()

assertEquals(Leftover.Backup(config, backup, found.startedAt), found)
```

## Reference

These differ by platform. Check them when a test passes on one target and fails on another.

- **The JVM runs the `mkfifo` command**, since Java has no call that makes a FIFO, so it needs one on the `PATH`. A
  missing command is `WriteFailed`.
- **The JVM sets the nine permission bits alone** on a file system without unix attributes.
- **`setModifiedAt` keeps microseconds on Linux and Apple.** It sets the access time there too. The JVM and Windows
  keep what the file system keeps.
- **On the JVM and Android `setModifiedAt` needs read access to the file.** So a file of yours with mode `000` is
  `WriteFailed` there. Linux native sets its time.
- **Windows**, native or JVM, keeps only whether the owner may write. `chmod` sets that as the file's read-only flag.
- Windows has no FIFOs, so `mkfifo` on Windows native is `WriteFailed` for every path `fs` would accept.
- Making a symlink on Windows needs Developer Mode or the right to make links.
- None of the Windows behaviour has been verified on Windows.

## License

[Apache-2.0](../LICENSE).
