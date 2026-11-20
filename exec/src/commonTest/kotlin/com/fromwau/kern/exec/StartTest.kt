package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartTest {
    @Test
    fun `echo gives its line on stdout then the exit and the close`() {
        val events = Command(listOf("/bin/echo", "hi")).events()
        assertEquals(ChildEvent.Output("hi\n", ChildStream.Stdout), events.first())
        assertEquals(setOf(ChildEvent.Exited(0), ChildEvent.OutputClosed), events.drop(1).toSet())
        assertEquals(3, events.size, "$events")
    }

    @Test
    fun `a missing program is ProgramNotFound`() = assertEquals(
        Err(ExecError.ProgramNotFound("kern-no-such-program")),
        SystemStarter.start(Command(listOf("kern-no-such-program"))),
    )

    @Test
    fun `a file that is not executable is NotExecutable`() = withFolder { folder ->
        val plain = "$folder/plain"
        assertTrue(ChildEvent.Exited(0) in sh("printf x > \"$1\" && chmod 644 \"$1\"", plain).events())

        assertEquals(Err(ExecError.NotExecutable(plain)), SystemStarter.start(Command(listOf(plain))))
        assertEquals(
            Err(ExecError.NotExecutable("plain")),
            SystemStarter.start(
                Command(listOf("plain"), environment = Environment.Exactly(mapOf("PATH" to folder.toString()))),
            ),
        )
    }

    @Test
    fun `a script whose interpreter is missing is InterpreterMissing and not ProgramNotFound`() = withFolder { folder ->
        val script = "$folder/script"
        val write = "printf '#!/kern-no-such-interpreter\\necho hi\\n' > \"$1\" && chmod 755 \"$1\""
        assertTrue(ChildEvent.Exited(0) in sh(write, script).events())

        assertEquals(
            Err(ExecError.InterpreterMissing(script)),
            SystemStarter.start(Command(listOf(script))),
        )
    }

    @Test
    fun `a missing working folder is WorkingDirectoryMissing`() {
        val missing = Path("/kern-no-such-folder/inside")
        assertEquals(
            Err(ExecError.WorkingDirectoryMissing(missing)),
            SystemStarter.start(Command(listOf("/bin/echo"), workingDirectory = missing)),
        )
    }

    @Test
    fun `a bare name is looked up in the child's PATH only`() = withFolder { folder ->
        makeTool(folder)

        val found = Command(listOf("tool"), environment = Environment.Exactly(mapOf("PATH" to "::$folder")))
        assertEquals("tool\n", found.events().text(ChildStream.Stdout))
        assertEquals(
            Err(ExecError.ProgramNotFound("sh")),
            SystemStarter.start(Command(listOf("sh"), environment = Environment.Exactly(emptyMap()))),
        )
    }

    @Test
    fun `a relative program is found from the working folder`() = withFolder { folder ->
        makeTool(folder)

        assertEquals("tool\n", Command(listOf("./tool"), workingDirectory = folder).events().text(ChildStream.Stdout))
    }

    @Test
    fun `argv reaches the child untouched`() = assertEquals(
        "a b||c*|\"q\"|",
        sh("printf '%s|' \"$@\"", "a b", "", "c*", "\"q\"").events().text(ChildStream.Stdout),
    )

    @Test
    fun `the environment is inherited extended or replaced`() {
        val extended = Command(
            listOf("/usr/bin/env"),
            environment = Environment.InheritWith(set = mapOf("KERN_EXEC_K" to "V"), unset = setOf("HOME")),
        ).events().text(ChildStream.Stdout).lines()
        assertEquals(true, "KERN_EXEC_K=V" in extended, "$extended")
        assertEquals(true, extended.any { it.startsWith("PATH=") }, "$extended")
        assertEquals(false, extended.any { it.startsWith("HOME=") }, "$extended")

        val replaced = Command(listOf("/usr/bin/env"), environment = Environment.Exactly(mapOf("KERN_EXEC_K" to "V")))
        assertEquals("KERN_EXEC_K=V\n", replaced.events().text(ChildStream.Stdout))
    }

    @Test
    fun `a NUL in an argument is InvalidCommand`() {
        val argv = listOf("/bin/echo", "a\u0000b")
        assertEquals(
            Err(ExecError.InvalidCommand(argv, "argument 1 holds a NUL character")),
            SystemStarter.start(Command(argv)),
        )
    }

    @Test
    fun `an environment name that is empty or holds an equals sign or NUL is InvalidCommand`() {
        val argv = listOf("/usr/bin/env")
        val reasons = mapOf(
            "" to "an environment variable name is empty",
            "A=B" to "environment variable name \"A=B\" holds '='",
            "A\u0000B" to "an environment variable name holds a NUL character",
        )
        for ((name, reason) in reasons) {
            assertEquals(
                Err(ExecError.InvalidCommand(argv, reason)),
                SystemStarter.start(Command(argv, environment = Environment.Exactly(mapOf(name to "v")))),
            )
        }
    }

    @Test
    fun `a NUL in an environment value is InvalidCommand`() {
        val argv = listOf("/usr/bin/env")
        assertEquals(
            Err(ExecError.InvalidCommand(argv, "environment variable K holds a NUL character")),
            SystemStarter.start(Command(argv, environment = Environment.InheritWith(mapOf("K" to "a\u0000b")))),
        )
    }

    @Test
    fun `the working folder is the child's`() = withFolder { folder ->
        assertEquals("$folder\n", sh("pwd -P").copy(workingDirectory = folder).events().text(ChildStream.Stdout))
    }

    private fun makeTool(folder: Path) {
        val script = "printf '#!/bin/sh\\necho tool\\n' > \"$1\" && chmod 755 \"$1\""
        assertTrue(ChildEvent.Exited(0) in sh(script, "$folder/tool").events())
    }
}
