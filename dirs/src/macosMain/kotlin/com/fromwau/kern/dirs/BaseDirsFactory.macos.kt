package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/** @param layout whether the roots follow the macOS convention or the XDG one many command-line tools use. */
public actual class BaseDirsFactory(private val layout: DirLayout = DirLayout.Native) {
    public actual fun create(): Result<BaseDirs, DirsError> = when (layout) {
        DirLayout.Native -> nativeBaseDirs()
        DirLayout.Xdg -> Ok(xdgBaseDirs(env("HOME") ?: NSHomeDirectory(), ::env, Path(NSTemporaryDirectory())))
    }

    // Kept identical to iosMain's create() by hand, rather than shared through an appleMain source set: one
    // duplicate costs less than another layer in the source tree. Change both.
    private fun nativeBaseDirs(): Result<BaseDirs, DirsError> {
        val support = userDirectory(NSApplicationSupportDirectory)
            ?: return Err(DirsError.UnableToResolve(DirKind.Config))
        val caches = userDirectory(NSCachesDirectory)
            ?: return Err(DirsError.UnableToResolve(DirKind.Cache))

        return Ok(
            BaseDirs(
                home = Path(NSHomeDirectory()),
                configHome = support,
                dataHome = support,
                stateHome = support,
                cacheHome = caches,
                tempHome = Path(NSTemporaryDirectory()),
                naming = AppNaming.Apple,
            ),
        )
    }
}

private fun userDirectory(directory: NSSearchPathDirectory): Path? {
    val path = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String
    return path?.let { Path(it) }
}
