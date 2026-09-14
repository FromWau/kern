package com.fromwau.kern.dirs

import android.content.Context
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

public actual class BaseDirsFactory(private val context: Context) {
    public actual fun create(): Result<BaseDirs, DirsError> {
        val files = Path(context.filesDir.absolutePath)
        val cache = Path(context.cacheDir.absolutePath)
        val noBackup = Path(context.noBackupFilesDir.absolutePath)

        return Ok(
            BaseDirs(
                home = files,
                configHome = files,
                dataHome = files,
                stateHome = noBackup,
                cacheHome = cache,
                tempHome = Path(cache, "tmp"),
            ),
        )
    }
}
