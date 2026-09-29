package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/** The file a run holds the lock on for as long as it uses its temp folder. */
private const val RUN_LOCK = ".run.lock"

/**
 * Runs [block] with this run's [AppDirs.temp] created and claimed, so [staleTempRuns] in another process knows
 * the run is alive. Wrap the work that uses the folder, usually the whole of `main`:
 *
 * ```kotlin
 * dirs.withRunTemp { temp -> runApp(temp) }
 * ```
 *
 * The claim is a [withLock] on `.run.lock` inside the folder, held until [block] returns, with no wait: a run id
 * already claimed by another process, which only a fixed id passed to [BaseDirs.forApp] can cause, is
 * [FileError.LockBusy]. Clearing the folder is still yours to do when the run ends.
 *
 * @return what [block] returned, or why the folder could not be created or claimed.
 */
public fun <T> AppDirs.withRunTemp(block: (Path) -> T): Result<T, FileError> =
    (temp / RUN_LOCK).withLock(waitMillis = 0) { block(temp) }

/**
 * The temp folders of this app's other runs that no live run has claimed, which is what a run that crashed or
 * was killed leaves behind, ready for [deleteRecursively]. This run's own folder is never listed.
 *
 * It only tells live runs apart when every run of the app claims its folder with [withRunTemp]: a folder whose
 * run never did counts as stale, and so does one whose run is starting at that moment and has not claimed it
 * yet. Asking leaves a `.run.lock` in each folder it looks at. A folder that cannot be looked up, or whose claim
 * cannot be tried at all, such as one this user may not write, ends the listing with that error.
 */
public fun AppDirs.staleTempRuns(): Result<List<Path>, FileError> {
    val runs = temp.parent ?: return Ok(emptyList())
    val entries = when (val listed = runs.list()) {
        is Result.Success -> listed.value
        is Result.Error -> return if (listed.error is FileError.NotFound) Ok(emptyList()) else listed
    }
    val stale = mutableListOf<Path>()
    for (run in entries) {
        if (run == temp) continue
        when (val type = run.fileType()) {
            is Result.Success -> if (type.value != FileType.Directory) continue
            // Gone since the listing, as a folder another cleanup just removed is.
            is Result.Error -> if (type.error is FileError.NotFound) continue else return Err(type.error)
        }
        when (val claimed = (run / RUN_LOCK).withLock(waitMillis = 0) { }) {
            is Result.Success -> stale += run
            is Result.Error -> if (claimed.error !is FileError.LockBusy) return Err(claimed.error)
        }
    }
    return Ok(stale)
}
