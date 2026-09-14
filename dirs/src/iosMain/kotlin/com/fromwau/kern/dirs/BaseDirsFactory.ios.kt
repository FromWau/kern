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

public actual class BaseDirsFactory {
    public actual fun create(): Result<BaseDirs, DirsError> {
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
            ),
        )
    }
}

private fun userDirectory(directory: NSSearchPathDirectory): Path? {
    val path = NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String
    return path?.let { Path(it) }
}
