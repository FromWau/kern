package com.fromwau.kern.exec

private const val NUL = '\u0000'

/**
 * What in this command no process can be given, as the problem of an [ExecError.InvalidCommand], or null when nothing.
 * A C string ends at its first NUL, and an environment entry is split at its first `=`.
 */
internal fun Command.unstartableReason(): String? {
    argv.forEachIndexed { index, argument ->
        if (NUL in argument) return "argument $index holds a NUL character"
    }
    val variables = when (val environment = environment) {
        Environment.Inherit -> emptyMap()
        is Environment.InheritWith -> environment.set
        is Environment.Exactly -> environment.variables
    }
    for ((name, value) in variables) {
        when {
            name.isEmpty() -> return "an environment variable name is empty"
            '=' in name -> return "environment variable name \"$name\" holds '='"
            NUL in name -> return "an environment variable name holds a NUL character"
            NUL in value -> return "environment variable $name holds a NUL character"
        }
    }
    if (workingDirectory?.toString()?.contains(NUL) == true) return "the working directory holds a NUL character"
    if (stdout == Output.ToStdout) return "stdout cannot go to stdout"
    for ((name, output) in listOf("stdout" to stdout, "stderr" to stderr)) {
        if (output is Output.File && NUL in output.path.toString()) return "the $name file holds a NUL character"
    }
    return null
}

/** `0666`, which the umask narrows: the mode a shell's `>` creates an output file with. */
internal const val NEW_FILE_MODE: Int = 0b110_110_110

/** The file [output] names, as this process opens it: a relative one from the child's working folder. */
internal fun Command.fileOf(output: Output.File): String =
    fromWorkingDirectory(output.path.toString(), workingDirectory).toString()
