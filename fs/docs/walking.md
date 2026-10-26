# Walking

Sometimes you need every entry under a folder: to list its files, to back it up, or to build a site from it.
`walkTopDown` visits each entry in a fixed order. You choose which links to follow and which folders to leave out.
`mirrorTo` builds on the walk. It makes one folder hold what another holds, through a [Writer](writer.md).

Part of [`fs`](../README.md).

```kotlin
fun Path.walkTopDown(
    symlinks: Symlinks = Symlinks.Inside,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
): Result<Sequence<Result<WalkEntry, WalkFailure>>, FileError>

fun Path.mirrorTo(
    dst: Writer,
    symlinks: Symlinks = Symlinks.AsLinks,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
    keep: (relative: Path) -> Boolean = { false },
    approveRemovals: (plan: RemovalPlan) -> List<Removal> = { it.defaultApproved() },
    outputOf: (relative: Path) -> String = { /* its names joined by `/` */ },
    isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean = { source, existing ->
        existing.size == source.size && existing.modifiedAt == source.modifiedAt
    },
    copy: (source: WalkEntry.File, out: Writer, name: String) -> EmptyResult<FileError> = { source, out, name ->
        out.copyFrom(source.path, name, keepAttributes = true).map { }
    },
): Result<MirrorReport, FileError>
```

## Rules

**A walk** yields four kinds of result:

- each regular file as a `WalkEntry.File`
- each folder below the root as a `WalkEntry.Folder`
- each entry it passes over as a `WalkEntry.Skipped`, with a `why`
- a `WalkFailure(path, relative, error)` for what it could not read.

Only under `AsLinks` does it yield a fifth kind, a `WalkEntry.Link`.

- Every entry has its `path`, and its `relative` path below the root. `relative` is never empty.
- The one failure with an empty `relative` is a root that can no longer be listed when you iterate the entries again.
  [Walk](#walk) has more.
- Order is fixed. Each folder's own entries come first, then its subfolders.
- Each is in name order by code units: `.hidden` first, `B.txt` before `a.txt`, so `a.txt`, `b/x`, `c.txt` walk as
  `a.txt`, `c.txt`, `b`, `b/x`.
- The root is looked up and listed when you call `walkTopDown`, so a missing, unreadable or FIFO root is the outer
  error.

**Symlinks.** `symlinks` decides what happens at a link.

| `Symlinks` | what the walk does with a link |
|---|---|
| `Inside`, a walk's default | follows it only when it finally ends inside the root's real folder |
| `Anywhere` | follows every link |
| `NoLinks` | follows none |
| `AsLinks` | follows none, and yields each as a `Link(path, relative, target)` |

- Under `Inside` the walk never reads outside the root.
- A `Link`'s `target` is as the link holds it.
- A followed link to a file is yielded as that file. A followed link to a folder is entered and yielded as a `Folder`.
- Each real folder is walked once. One inside the root is walked under its own path.
- A link to a folder waits until every folder reached without a link is walked, so `alias -> assets` is
  `AlreadyEntered(assets)`, even when it sorts first.

**Leaving things out.**

- `exclude` names folders the walk never enters and files it passes over. It matches by real path, however the walk
  reaches them.
- `enter` is asked about each folder below the root before it is walked. `false` passes the folder over as
  `Skipped(Refused)`. The root is never passed to it.

**A mirror** makes `dst` hold what the source holds.

- `mirrorTo(dst)` removes what the source no longer has.
- It plans the whole run before it changes anything in `dst`.
- By default, `AsLinks`, it makes each link again, as a link.
- An error with one entry goes into the `MirrorReport`, and the mirror goes on with the rest.
- `report.unclean` lists each reason a run is not clean. `isClean` is `unclean.isEmpty()`.
- A printed `MirrorReport` is one compact line: each list's size and its first five entries. Its fields hold the full
  lists.
- In that line, a name over 64 characters is cut and followed by its length in bytes.

## Tasks

### Walk a tree

```kotlin
val entries = root.walkTopDown(exclude = setOf(backup)).getOrElse { return warn(it) }
for (entry in entries) {
    entry.onError { warn(it.error) }
    val file = entry.getOrNull() as? WalkEntry.File ?: continue
    println("${file.relative}: ${file.size} bytes")
}
```

### Mirror a folder

```kotlin
val dst = published.writer().getOrElse { return warn(it) }
val report = root.mirrorTo(dst, enter = { it.path.name != ".git" }).getOrElse { return warn(it) }
report.failures.forEach { warn(it.error) }
report.notRemoved.forEach { warn(it) }
(report.held + report.passedOver).forEach { println("passed over ${it.relative}: ${it.why}") }
```

`report.isClean` follows the rules in [Mirror report](#mirror-report).

A source that yields nothing removes nothing by default. What `dst` holds is listed in `removalsDeclined`, and
`report.sourceEmpty` is true. The run is not clean unless `dst` was empty too.

By default a run also keeps what `dst` has at the place of an entry the walk passed over. One example is a folder
that is now a link leading out of the source. Its copy stays and is listed in `removalsDeclined`, while the rest goes.

### Build a site

```kotlin
fun pageOutput(relative: Path): String {
    val name = relative.toString().replace('\\', '/')
    return if (relative.extension == "md") "${name.removeSuffix(".md")}.html" else name
}

val site = siteFolder.writer().getOrElse { return warn(it) }
val report = pages.mirrorTo(
    site,
    symlinks = Symlinks.Inside,
    keep = { it == Path(".git") || it == Path("CNAME") },
    outputOf = ::pageOutput,
    isCurrent = { source, existing ->
        if (source.path.extension == "md") {
            existing.modifiedAt == source.modifiedAt
        } else {
            existing.size == source.size && existing.modifiedAt == source.modifiedAt
        }
    },
    copy = { source, out, name ->
        val written = if (source.path.extension == "md") {
            source.path
                .readText(maxBytes = 1_048_576)
                .flatMap { out.writeText(name, render(it), modifiedAt = source.modifiedAt) }
        } else {
            out.copyFrom(source.path, name, modifiedAt = source.modifiedAt)
        }
        written.map { }
    },
).getOrElse { return warn(it) }
```

- `symlinks = Symlinks.Inside` renders a link to a page as the page. Made again as a link, it would lead to a `.md`
  the site does not have.
- `outputOf` writes each `a.md` as `a.html`. It turns a Windows `\` into the `/` a `Writer` takes.
- A folder keeps its name, as [One output each](#one-output-each) shows with `thing.md/`.
- `keep` keeps the `.git` and the `CNAME` that only the site has.
- `copy` gives each page its source's modification time. A rendered page never matches its source's size, so
  `isCurrent` takes a page as current when its time is its source's.
- So a page whose source was replaced by an older file is rendered again. The same goes for such an asset: the default
  check of size and modification time copies it again.
- An asset is copied with its source's modification time and the site's own mode, `644` under umask `022`, so one
  that is `600` in the source is still readable on the site. `keepAttributes = true` would keep that `600`.
- `copy` reads each page with a size cap. A page past it is a failure in the report.

**Sharp edges**

- A site built before `alias` became a link to a folder the walk enters as well, such as `alias -> assets`, keeps its
  old `alias/` copy on every run. [Links in `dst`](#links-in-dst) says how to clear it.
- `isCurrent` never sees `render`, so a changed renderer leaves every page current. Render them all again once with an
  `isCurrent` that answers false for a `.md`, or delete the site's `.html` pages first.

### Back up a folder with its links as links

```kotlin
val backup = backupDisk.writer(create = false).getOrElse { return warn(it) }
val report = projects.mirrorTo(backup).getOrElse { return warn(it) }
report.passedOver.forEach { println("not backed up, it leads out: ${it.relative}") }
report.failures.forEach { warn(it.error) }
report.removalsDeclined
    .mapNotNull { it.why as? RemovalReason.AtPassedOver }
    .groupBy { it.place }
    .forEach { (place, kept) -> println("kept the old copy at $place: ${kept.size} entries") }
```

- Each link in `projects` is made in the backup with its target as written. That is what `AsLinks`, the default,
  does.
- So `current -> releases/43` stays a link, and the release is backed up once, under `releases`.
- A link whose target leads out of `projects` is not made. Examples are `../shared` and any absolute target. It is in
  `passedOver` as `LeadsOutside`, and the run is not clean.
- A folder an earlier run backed up may now be such a link. It keeps its copy, because the default `approveRemovals`
  leaves its removals out.
- Those removals are listed in `removalsDeclined`, each naming that folder as its `place`. A file gone from
  `projects` still goes.
- Such a run is not clean. Here is a CI check that accepts it, and still fails on anything else in `unclean`:

```kotlin
val passed = report.unclean.all { unclean ->
    when (unclean) {
        is MirrorReport.Unclean.Kept -> unclean.removal.why is RemovalReason.AtPassedOver
        is MirrorReport.Unclean.LinkNotMade -> true
        is MirrorReport.Unclean.Held -> unclean.entry.why == WalkSkip.DanglingLink
        is MirrorReport.Unclean.Failed, is MirrorReport.Unclean.NotRemoved -> false
    }
}
```

- `unclean` lists each reason `isClean` is false. There is one `Unclean` per entry of the report it names.
- The check accepts the copies kept at a place passed over. It accepts each link not made because it leads out.
- It accepts a dangling link too. Only a mode that follows links holds one. `AsLinks` makes it again as a link.
- `held` entries that `exclude` or `enter` asked for are never in `unclean`. Nor are links to `dst`.

### Back up to a stick that keeps coarse times

```kotlin
/** A file's size and modification time, as a run saw them. */
data class Stamp(val size: Long, val modifiedAt: Instant)

val stick = stickFolder.writer(create = false).getOrElse { return warn(it) }
val copies = mutableMapOf<Path, Pair<Stamp, Stamp>>()
val report = photos.mirrorTo(
    stick,
    isCurrent = { source, existing ->
        val stamps = Stamp(source.size, source.modifiedAt) to Stamp(existing.size, existing.modifiedAt)
        val current = lastRun[source.relative] == stamps
        if (current) copies[source.relative] = stamps
        current
    },
    copy = { source, out, name ->
        out.copyFrom(source.path, name, modifiedAt = source.modifiedAt).map { written ->
            val copy = written.info
            copies[source.relative] = Stamp(source.size, source.modifiedAt) to Stamp(copy.size, copy.modifiedAt)
        }
    },
).getOrElse { return warn(it) }
val leftAlone = report.held.map { it.relative } + report.failures.map { it.relative } +
    report.removalsDeclined.map { it.path }
for ((relative, stamps) in lastRun) {
    if (generateSequence(relative) { it.parent }.any { it in leftAlone }) copies[relative] = stamps
}
```

- FAT keeps a modification time to 2 seconds, so a copy there rarely has its source's time, and the default
  `isCurrent` copies every file again on every run.
- `lastRun` is a `Map<Path, Pair<Stamp, Stamp>>` you load before the run. It holds each source's stamp and its copy's
  stamp. The copy's stamp is as the `Written` that `copyFrom` answers describes it.
- A file is current while both stamps are as recorded.
- `copies` is the record to save for the next run. It holds each file copied or current.
- `copies` also carries rows over from `lastRun`: each file in or below an entry the run held, could not read or did
  not remove. That file's copy stays on the stick.
- A file gone from `photos` drops out once its copy is removed.
- A run whose `photos` yields nothing, such as an unmounted card, removes nothing and keeps every row. Each copy is a
  `Kept` in `unclean`. The next run with the photos back copies none of them again.
- A source written since, or a copy changed on the stick, no longer matches. It is copied again.

### Refuse a run that would remove too much

```kotlin
fun fewRemovals(plan: RemovalPlan): List<Removal> {
    val wanted = plan.defaultApproved()
    return if (wanted.size > 50) emptyList() else wanted
}

val backup = backupDisk.writer(create = false).getOrElse { return warn(it) }
val report = disk.mirrorTo(backup, approveRemovals = ::fewRemovals).getOrElse { return warn(it) }
if (report.removalsDeclined.isNotEmpty()) {
    println("${report.removalsDeclined.size} entries kept in the backup, check $disk")
}
```

- A disk that mounts with nothing on it never empties the backup. `defaultApproved()` answers no removal for a plan
  with `sourceEmpty`.
- A disk holding only its `lost+found` yields a folder, so the limit of 50 removals is what keeps a larger backup.
- An approver of your own replaces the default. One built on `defaultApproved()` keeps the default's rule.
- That rule: a folder that is now a link leading out of `disk` keeps its copy in the backup. A file gone from `disk`
  goes.
- `create = false` makes no folder where a backup disk that is not mounted should be. That is `NotFound`.

## Reference

You do not need this part on a first read. Come back when a walk or a mirror does something you did not expect.

### Walk

- A `File` carries its `size`, `modifiedAt` and `viaLink`, all as they were when the walk reached it.
- `viaLink` says whether the file's `path` is a symlink to the file.
- A regular file as the root yields itself, with its name as `relative`.
- A symlink as the root is entered whatever the mode.
- A folder root is never yielded itself.
- Each subfolder is yielded as a `Folder` just before its own entries.
- A folder that cannot be listed is yielded before its failure. One gone by the time the walk lists it is
  `Skipped(Gone)` instead.
- Iterating the entries a second time walks afresh. A root that can no longer be listed is then the walk's one
  failure, with the root as its `path` and an empty `relative`.
- What `enter` throws ends the iteration.
- An entry whose path is past the system's limit, `PATH_MAX`, is a failure `Inaccessible(cause = Other)`. That limit
  is 4096 bytes on Linux.
- In a mirror, the folders above such an entry are still made in `dst`.

### Symlinks

- Under `Inside`, a link in `state` to `../state/b` is followed to `b`. It steps out on its way but ends inside.
- A [Writer](writer.md#rules) on `state` refuses that same link as `Escapes`.
- Where links are followed, a folder inside the root is walked under its own path. With `a -> c` beside `c`, the
  folder is walked as `c/...`. `a` is `AlreadyEntered(firstRelative = c)`, though `a` sorts first.
- Each link to a folder waits until every folder reached without a link is walked. Then the links go in the order the
  walk met them.
- A link to a folder not walked yet, such as one outside the root under `Anywhere`, is walked under its own name at
  that point. The links to folders met below it join the end.
- The walk holds one entry per link waiting.
- A waiting link may change before its turn, for example be pointed at a file or removed. It is yielded as what it is
  then. That happens at that later point of the walk, not among its folder's entries.
- A failure's `path` is spelled from the root you passed. For a folder the walk reached only through a link, it is
  spelled under the link the walk took.
- A failure's `relative` says where in the tree it was met: `out/broken.txt` for `out -> ../shared` under `Anywhere`.
- In a mirror under `Inside` or `Anywhere`, an `AlreadyEntered` link holds nothing. With `a -> c` beside `c`, `dst`
  gets `c/...`.
- What `dst` has at `a` is then a removal `AtPassedOver(AlreadyEntered(c), a)`. The default `approveRemovals` leaves
  it out, so it stays.

### Leaving folders out

- Each `exclude` entry is resolved with `realPath()` once, when you call `walkTopDown`.
- An `exclude` entry that nothing is at excludes nothing. One that cannot be resolved for another reason is the outer
  error.
- The root itself is always walked.
- A folder in `exclude`, or below one, is `Skipped(Excluded)`. It is never read, and `enter` is not asked about it.
- A file in `exclude` is `Skipped(Excluded)` too, so you can leave a source file named like a leftover out of a
  mirror, and the mirror stays clean.
- A link to an excluded folder, or to a file in one, is `Skipped(Excluded)` under `Inside` and `Anywhere`. Under
  `NoLinks` it is `Skipped(Link)`, and under `AsLinks` a `Link`, as any link is.
- So no file in `backup` is yielded through a link either.
- A link in `exclude` leaves out what it leads to under `Inside` and `Anywhere`.
- `AsLinks` and `NoLinks` follow no link. Under them, a link in `exclude` is matched by its own place: the real path
  of its folder plus its name.
- That link is `Skipped(Excluded)`, and a mirror does not make it. What it leads to is still walked and copied.
- `enter` is asked when the walk meets the folder among its parent's entries, in name order. That is before the
  parent's later entries, and before the folder or any sibling is walked.
- A link to a folder is asked about when its turn comes.
- Where links are followed, `enter` is not asked about a folder already walked. A folder it refused counts as not
  walked, so a later path to it is asked again.

### Skipped entries

| `WalkSkip` | what was passed over |
|---|---|
| `DanglingLink` | a symlink whose target is missing, where links are followed |
| `LinkLoop` | a symlink whose chain goes round or runs longer than the system follows |
| `Gone` | an entry gone by the time the walk looks it up, or a folder gone by the time it lists it |
| `NotFileOrFolder(type)` | a FIFO, device or socket |
| `AlreadyEntered(firstRelative)` | a link to a folder already walked under `firstRelative`, null for the root |
| `Excluded` | a file or folder `exclude` names, or anything below such a folder |
| `Refused` | a folder `enter` refused |
| `LeadsOutside` | a link `Inside` did not follow |
| `Link` | any link under `NoLinks` |

- A link to a folder above is `AlreadyEntered`.
- `Excluded` also covers a link to a file in an excluded folder. Under `AsLinks` and `NoLinks`, it covers a link
  `exclude` names.
- In a mirror report, `LeadsOutside` is also a link `AsLinks` yielded that leads out and was not made.

### Mirror report

| list | side it speaks of |
|---|---|
| `copied`, `unchanged` | both: each a `Mirrored(relative, output)` |
| `removed` | `dst`: paths relative to its root |
| `removalsDeclined` | `dst`: each a `Removal(path, why)` |
| `held`, `passedOver`, `failures` | the source: entries of its walk |

- A `Mirrored` holds the path in the source and its output in `dst`.
- A `Removal` holds the path relative to the root of `dst`, and why it was planned.
- `copied` lists the files copied. `unchanged` lists the files found current and left alone.
- With `outputOf` renaming `a.md` to `a.html`, its `Mirrored` is `Mirrored(a.md, a.html)`.
- `removed` lists the entries removed, in the order removed.
- Under `AsLinks`, a link made or switched is in `copied`, and one already right is in `unchanged`, so `copied.size`
  counts the links made as well as the files.
- A link at a file's output that the file replaced is listed in `removed`, and the file in `copied`.
- `held` lists each entry the walk passed over that holds what `dst` has at its place.
- A folder the walk could not read is in `failures` instead. It holds what `dst` has at its place all the same.
- `passedOver` lists each entry passed over as `LeadsOutside`, `Link`, `NotFileOrFolder(type)` or `AlreadyEntered`,
  in walk order. Such an entry holds nothing.
- Under `AsLinks`, `passedOver` lists each link not made because it leads out, as `LeadsOutside`.
- `failures` lists:
  - each entry the walk could not read
  - each file or folder that could not be copied, made or cleared
  - each entry below a folder that could not be cleared
  - each entry whose output another took.
- `notRemoved` lists what kept a stale entry from going, once per path.
- `removalsDeclined` lists each planned removal that stayed, with its `why`:
  - each removal `approveRemovals` left out
  - each folder kept because it still holds one of those
  - the rest of an entry's way, once part of it stays.
- Printed, each entry of `removalsDeclined` shows its path.
- `sourceEmpty` is true when the source's walk yielded no file or folder, as the plan's `sourceEmpty` says.
- `unclean` lists why the run is not clean. Each is an `Unclean` naming one entry of another list:
  - `Failed(failure)`
  - `NotRemoved(error)`
  - `Kept(removal)` for one in `removalsDeclined`
  - `Held(entry)` for one in `held` that `exclude` or `enter` did not ask for, and that is no link to `dst`
  - `LinkNotMade(entry)` for a link `AsLinks` yielded that leads out.
- `isClean` is `unclean.isEmpty()`.
- So a FIFO, socket or device in the source makes a run unclean only while what `dst` has at its place stays. The run
  that removes it is clean, and so are the runs after it.
- A dangling link or a link loop where links are followed, and a folder that cannot be read, are held and keep every
  run unclean.
- Where both file systems keep the source's time precision, a mirror needs no index of its own. `isCurrent` compares
  each source file against the file at its output in `dst`.
- Where one does not, see [Back up to a stick that keeps coarse times](#back-up-to-a-stick-that-keeps-coarse-times).

### Mirror rules

A run plans first and changes `dst` after. Each part below covers one step, from the plan to what a run refuses
outright.

#### Planned first

Nothing in `dst` changes until a walk of the source and one of `dst` have planned the whole run. The plan holds paths
and types, never what a file holds.

- `approveRemovals` is asked once, before anything is copied or removed. It is not asked at all when there is nothing
  to remove.
- Its `RemovalPlan` holds:
  - the `removals`, each a `Removal(path, why)` relative to `dst`, in the order they would go
  - how many are `files` and `folders`
  - `dstEntries`, with a kept or held folder counted as one
  - `sourceEmpty`.
- `why` is one of three:
  - `Stale` for what no entry yields or holds
  - `InTheWay` for what stands where an entry goes
  - `AtPassedOver(skip, place)` for what lies at the place of an entry in `passedOver`. That may be a copy you still
    want.
- `place` is that entry's relative path. It is `photos` for every removal in the old copy of a folder `photos`.
- `sourceEmpty` is true when the source's walk yielded no file or folder. Skips and failures alone do not count.
- The default `approveRemovals` is `plan.defaultApproved()`. It answers no removal for such a run. Otherwise it
  answers every removal but those `AtPassedOver`.
- The removals come in this order: the stale leftovers, then what stands in each entry's way in walk order, then the
  other stale entries. Stale entries go in reverse walk order.
- Only the removals `approveRemovals` answers are carried out, matched by path. One not in the plan is ignored.
- Each other removal stays and is listed in `removalsDeclined`. So is a folder that would still hold one of them.
- An entry whose way is not cleared in full, because a removal in it stays, keeps all that stands there. The entry is
  then a failure: `NotADirectory(output, type)` for a folder, `NotRegularFile(output, type)` for a file.
- Each entry below such a folder is a failure with the same error, so nothing is written through a link kept there.
  The rest are copied as usual.

#### Copied

- Each folder is made in walk order, so an empty one is copied too.
- A file is left alone when a regular file at its output is current, as `isCurrent` answers. By default that is when
  size and modification time match.
- `isCurrent` is never asked about a symlink or anything else at the output.
- Every other file goes through `copy`. By default that is `Writer.copyFrom` with `keepAttributes`.
- That copy is renamed into place, so a hard link in `dst` is cut rather than written through. The next run finds the
  copy current where both file systems keep the source's time precision.
- That copy keeps the source's permission bits within `755`, so a `600` file, such as a private writer's, stays `600`.
- Under `Inside` or `Anywhere`, a link to a file that the walk follows is copied as the file it leads to.
- Under `AsLinks`, the default, each link is made under its own name with `Writer.switchSymlink`, its target as
  written.
- A link in `dst` with the same target is left alone. One with another target is switched in one step.
- A link whose target leads out of the source is not made. That is any absolute target, or one that leads out by its
  names at any step, such as `../shared` or `../src/a` from the source's top.
- What `dst` has at that link's place is a removal `AtPassedOver(LeadsOutside, place)`. `place` is the link's path
  relative to the source.
- A file or folder where a link goes stands in its way and is removed first. With that removal declined, the link is a
  failure `AlreadyExists(output, type)`.
- A link, FIFO, socket or device at a file's output is removed within `dst`, as an entry of the other type is. The
  file is copied in its place, whatever `copy` does.

**Sharp edges**

- The default `isCurrent` never reads the source, so a file made unreadable, as by `chmod 000`, still counts as
  current while its size and modification time are unchanged. Its old output stays.
- A source file or folder named like a leftover, `.x.<id>.tmp`, is not copied. The `Writer` refuses to make it, so it
  is a failure `InvalidRelativePath` with `NameProblem.LeftoverName`.
- That way a leftover scan of `dst` never mistakes a copy for a crash's.
- Each entry below such a folder is a failure too, its `BadName` naming the folder.

#### One output each

- A folder goes to its own relative path. `outputOf` is never asked about a folder the walk yields.
- A file goes into its folder's output, under the last name of what `outputOf` answers for its relative path, so a
  tree stays one tree.
- So `outputOf` renames only a file's last name.
- A site that rearranges folders writes its pages through a `Writer` in a copy of its own, not with `mirrorTo`. The
  next `mirrorTo` run would remove what lies outside its outputs.
- With the site builder's `pageOutput`, a folder `thing.md/` stays `thing.md/`, since `outputOf` is never asked about
  it. Its `x.md` becomes `thing.md/x.html`.
- The first entry in walk order to name an output takes it. A later entry whose `outputOf` names the same output is
  not copied or made. It is a failure `OutputClash(output, firstRelative)`.
- Walk order is fixed, so the same entry wins every run. With `x.md` written as `x.html` beside a source `x.html`, the
  source `x.html` is copied and `x.md` is the failure.
- Nothing below a folder that loses its output is copied, made or reported. The folder alone is the failure.
- An output name over 255 bytes is not written. That entry is a failure
  `InvalidRelativePath(dst.root, output, BadName(TooLong))` naming the output.

#### Links in `dst`

Everything done in `dst` goes through the `Writer`, so it stays below its root.

Someone may leave a symlink in `dst` where the source has a folder or a file. That link stands in the entry's way. It
is in the plan, and it is removed as a link just before the entry is placed. It is never written through.

A `copy` of your own is handed the same `Writer` and the output's name, with nothing there.

A link to a folder the walk already entered is `AlreadyEntered` under `Inside` and `Anywhere`. One example is
`docs-v1 -> docs` where an earlier run copied a folder `docs-v1/`. Another is `link-to-dir -> sub` that an `AsLinks`
run made. What `dst` has there is a removal `AtPassedOver` that the default leaves out, so it stays on every run.

Clear it once in one of two ways. Run with an approver that answers what the default answers plus those removals. Or
remove it through the writer with `delete` or `deleteRecursively`:

```kotlin
val report = site.mirrorTo(
    out,
    symlinks = Symlinks.Inside,
    approveRemovals = { plan ->
        val atTwins = plan.removals.filter { removal ->
            val why = removal.why
            why is RemovalReason.AtPassedOver && why.skip is WalkSkip.AlreadyEntered
        }
        if (plan.sourceEmpty) emptyList() else plan.defaultApproved() + atTwins
    },
).getOrElse { return warn(it) }
```

- `defaultApproved()` holds no `AtPassedOver` removal, so none is answered twice.
- A source whose walk yielded no file or folder, such as one holding only `self -> .`, removes nothing, as by default.
- That run's report is clean when nothing else stays, and so are the runs after it.
- What `dst` has at a link leading out is also `AtPassedOver`. It stays, since that may be a copy you still want.

A link to a file that an `AsLinks` run made stands in the way of that file under `Inside` or `Anywhere`. It is removed
as `InTheWay`, and the file is copied in its place.

#### Held

- An entry the walk skipped, such as a folder `enter` refused or a dangling link, keeps everything `dst` holds at its
  place. So does an entry the walk could not read.
- That includes whole folders, under both `relative` and the output the entry would have as a file.
- So the copy of a file that is now a dangling link stays as long as the link does. One `Gone` goes on the next run
  that no longer lists it.
- An entry passed over as `LeadsOutside`, `Link`, `NotFileOrFolder(type)` or `AlreadyEntered` holds nothing. It is
  listed in `passedOver`, not `held`.
- What `dst` has at its place is a removal `AtPassedOver`. The default `approveRemovals` leaves it out, so it stays and
  the report is not clean.
- A copy made under `Anywhere` through a link leading outside the source is removed by a later run under `Inside`. One
  made through any link is removed by a later run under `NoLinks`. Either way, `approveRemovals` must answer its
  removals.

#### Removed

- What `dst` holds that the walk did not yield, and no entry holds, is removed once copying is done. It goes in
  reverse walk order, so each folder goes after what it holds.
- A held folder is never entered. Stale leftovers go before the copying, as below.
- The folders an output or a held path lies in stay.
- A temp or backup a killed write left in `dst`, `.x.<id>.tmp` or `.x.<id>.bak`, is stale like any entry the source
  does not have. It is listed in `removed`.
- It is removed before anything is copied, since a backup beside a file would stop its copy as `PendingBackup`.
- Nothing is removed before the overlap check below has passed.
- So do not scan or discard leftovers in a mirror's `dst` yourself. Where `dst` holds the source, a discard after a
  scan of that `dst` discards a source file named like a leftover.
- A folder of `dst` holding an entry it could not read stays, and so does each folder above it.
- `keep(relative)` true keeps an entry only `dst` has. It is never removed, nor is each folder above it.
- A kept folder is never entered, and none of them appears in the report.
- `keep` is never asked about what stands in an entry's way.
- `keep` is also asked about what `dst` holds at the place of an entry in `passedOver`. That is otherwise planned for
  removal.
- What stands in an entry's way is removed just before the folder is made or the file copied. That is:
  - a file or link where the source now has a folder
  - a folder, with all it holds, where the source now has a file
  - a link, FIFO, socket or device at a file's output.

**Sharp edges**

- The removals follow the source, so a wrong source would empty `dst`. Check what you pass, or refuse a run with an
  `approveRemovals` that answers no removal.

#### Refused

- A source that is not a folder is `NotADirectory`. Its walk yields only itself, so the removals would empty `dst`.
- A source that is the root of `dst` or lies below it, by its `realPath()`, is `MirrorOverlap(root, dst.root)`.
- Neither changes anything.
- The walk of the source excludes `dst`, so a `dst` inside the source is never mirrored into itself, nor reported as
  held.
- A link in the source may lead to `dst` or into it. Under `Anywhere`, and under `Inside` with `dst` inside the
  source, such a link is in `held` as `Excluded`, since `dst` is excluded. The run stays clean.
- A link to `dst` is left alone, as if excluded. What an earlier `AsLinks` run made there stays until removed.
- Under `Inside` with `dst` outside the source, such a link is `LeadsOutside`, as any link leading out is.
- Under `AsLinks`, a link to a `dst` inside the source is made when its relative target stays in the source. An
  absolute one is `LeadsOutside`, as any is.
