@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.exec

import com.fromwau.kern.exec.spawn.kern_exec_environment
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes
import platform.posix.strlen

public actual object SystemStarter : Starter {
    actual override fun start(command: Command): Result<Child, ExecError.StartError> {
        val name = command.argv.firstOrNull() ?: return Err(ExecError.EmptyCommand)
        command.unstartableReason()?.let { return Err(ExecError.InvalidCommand(command.argv, it)) }
        val environment = childEnvironment(command.environment, ::inheritedEnvironment)
        return lookUp(name, environment["PATH"]?.decodeToString(), command.workingDirectory).flatMap { program ->
            startLinuxChild(command, name, program, environmentEntries(environment))
        }
    }
}

/** This process's variables as the bytes it holds; for a name set twice the first, as `getenv` reads it. */
private fun inheritedEnvironment(): Map<String, ByteArray> {
    val variables = LinkedHashMap<String, ByteArray>()
    val entries = kern_exec_environment() ?: return variables
    var index = 0
    while (true) {
        val entry = entries[index++] ?: return variables
        val (name, value) = splitEntry(entry.readBytes(strlen(entry).toInt())) ?: continue
        if (name !in variables) variables[name] = value
    }
}
