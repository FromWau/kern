package com.fromwau.kern.fs.usage

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.current
import com.fromwau.kern.fs.*
import com.fromwau.kern.result.*

// The README's Getting started, as written there; GettingStartedJvmTest runs it as its own process.

sealed interface AppError : ResultError {
    data class Dirs(val error: DirsError) : AppError
    data class File(val error: FileError) : AppError
}

fun saveSettings(): EmptyResult<AppError> = result {
    val dirs = BaseDirs.current().flatMap { it.forApp("myapp") }.bind(AppError::Dirs)
    val settings = dirs.config / "settings.toml"
    // A missing file reads as the defaults. Checking exists() first would race a file that goes away in between.
    val text = settings.readText(maxBytes = 1_048_576).orIfNotFound { "theme = \"light\"" }.bind(AppError::File)
    settings.writeText(text).bind(AppError::File)
}

fun main() {
    saveSettings().onError { println("settings not saved: $it") }
}

/** Starts [main] under a class name the test can launch. */
object GettingStarted {
    @JvmStatic
    fun main(args: Array<String>) = com.fromwau.kern.fs.usage.main()
}
