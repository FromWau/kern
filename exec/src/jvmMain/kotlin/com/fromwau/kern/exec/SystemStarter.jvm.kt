package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import java.io.File

public actual object SystemStarter : Starter {
    actual override fun start(command: Command): Result<Child, ExecError.StartError> {
        val name = command.argv.firstOrNull() ?: return Err(ExecError.EmptyCommand)
        command.unstartableReason()?.let { return Err(ExecError.InvalidCommand(command.argv, it)) }
        unableToStart?.let { return Err(ExecError.Unsupported(it)) }
        val environment = childEnvironment(command.environment, ::inheritedEnvironment)
        val workingDirectory = command.workingDirectory
        return lookUp(name, environment["PATH"]?.decodeToString(), workingDirectory).flatMap { program ->
            // posix_spawn does not tell a missing folder from a missing program.
            if (workingDirectory != null && !File(workingDirectory.toString()).isDirectory) {
                Err(ExecError.WorkingDirectoryMissing(workingDirectory))
            } else {
                startFfmChild(command, program, environmentEntries(environment))
            }
        }
    }
}
