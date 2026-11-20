package com.fromwau.kern.exec

import java.io.File
import kotlinx.io.files.Path

internal actual fun programFile(path: Path): ProgramFile {
    val file = File(path.toString())
    return when {
        !file.isFile -> ProgramFile.Missing
        !file.canExecute() -> ProgramFile.NotExecutable
        else -> ProgramFile.Executable
    }
}
