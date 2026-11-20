package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/** What a candidate for a program is. */
internal enum class ProgramFile { Missing, NotExecutable, Executable }

/** Whether [path] is a regular file this process may execute; anything but a regular file is [ProgramFile.Missing]. */
internal expect fun programFile(path: Path): ProgramFile

/**
 * The path to start [program] by, from the child's working folder. A name with a `/` is used as given. A bare name is
 * the first executable regular file named so in the folders of [path], the child's `PATH`, with blank entries skipped
 * and relative ones taken from [workingDirectory].
 */
internal fun lookUp(program: String, path: String?, workingDirectory: Path?): Result<String, ExecError.StartError> {
    if ('/' in program) return Ok(program)
    var seenNotExecutable = false
    for (folder in path.orEmpty().split(':')) {
        if (folder.isBlank()) continue
        val candidate = "$folder/$program"
        when (programFile(fromWorkingDirectory(candidate, workingDirectory))) {
            ProgramFile.Executable -> return Ok(candidate)
            ProgramFile.NotExecutable -> seenNotExecutable = true
            ProgramFile.Missing -> {}
        }
    }
    return Err(if (seenNotExecutable) ExecError.NotExecutable(program) else ExecError.ProgramNotFound(program))
}

/** [candidate] as the child sees it from [workingDirectory]: a relative path is taken from there. */
internal fun fromWorkingDirectory(candidate: String, workingDirectory: Path?): Path =
    if (workingDirectory == null || candidate.startsWith('/')) Path(candidate) else Path(workingDirectory, candidate)

/**
 * What "no such file" from starting [program], the path [lookUp] gave for [name], means: the program is gone, or, when
 * it is there and may be run, what it needs to run is.
 */
internal fun programMissing(command: Command, name: String, program: String): ExecError.StartError =
    if (programFile(fromWorkingDirectory(program, command.workingDirectory)) == ProgramFile.Executable) {
        ExecError.InterpreterMissing(name)
    } else {
        ExecError.ProgramNotFound(name)
    }
