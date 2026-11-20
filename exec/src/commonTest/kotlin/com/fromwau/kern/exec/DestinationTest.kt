package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationTest {
    @Test
    fun `discarded output gives OutputClosed at once and then the exit`() = assertEquals(
        listOf(ChildEvent.OutputClosed, ChildEvent.Exited(3)),
        sh("echo out; echo err >&2; exit 3").copy(stdout = Output.Discard, stderr = Output.Discard).events(),
    )

    @Test
    fun `a relative file is taken from the working folder and stderr to stdout lands in it too`() =
        withFolder { folder ->
            val events = sh("echo one; echo two >&2")
                .copy(workingDirectory = folder, stdout = Output.File(Path("out.log")), stderr = Output.ToStdout)
                .events()

            assertEquals(listOf(ChildEvent.OutputClosed, ChildEvent.Exited(0)), events)
            assertEquals("one\ntwo\n", contentOf(Path(folder, "out.log")))
        }

    @Test
    fun `a file is emptied first unless appended to`() = withFolder { folder ->
        val log = Path(folder, "log")
        val _ = sh("echo old > \"$1\"", log.toString()).events()

        val _ = sh("echo new").copy(stdout = Output.File(log)).events()
        val emptied = contentOf(log)
        val _ = sh("echo more").copy(stdout = Output.File(log, append = true)).events()

        assertEquals(listOf("new\n", "new\nmore\n"), listOf(emptied, contentOf(log)))
    }

    @Test
    fun `stderr to a piped stdout comes as stdout`() = assertEquals(
        listOf(ChildEvent.Output("a\n", ChildStream.Stdout)),
        sh("echo a >&2").copy(stderr = Output.ToStdout).events().filterIsInstance<ChildEvent.Output>(),
    )

    @Test
    fun `an inherited stdout is this process's own`() {
        // Read before any redirection: dash points its own fd 1 at the target of a command's >&2 while it runs.
        val script = "child=\$(readlink /proc/\$\$/fd/1); parent=\$(readlink /proc/\$PPID/fd/1); " +
            "echo \"\$child \$parent\" >&2"
        val events = sh(script).copy(stdout = Output.Inherit).events()

        val (child, parent) = events.text(ChildStream.Stderr).trim().split(' ')
        assertEquals(parent, child)
    }

    @Test
    fun `stdout to stdout is InvalidCommand`() {
        val command = Command(listOf("/bin/true"), stdout = Output.ToStdout)
        assertEquals(
            Err(ExecError.InvalidCommand(command.argv, "stdout cannot go to stdout")),
            SystemStarter.start(command),
        )
    }

    @Test
    fun `an output file that cannot be opened is OutputFileFailed and starts nothing`() {
        val missing = Path("/kern-no-such-folder/out.log")
        assertEquals(
            Err(ExecError.OutputFileFailed(missing, ChildStream.Stderr, "No such file or directory")),
            SystemStarter.start(Command(listOf("/bin/true"), stderr = Output.File(missing))),
        )
    }

    private fun contentOf(file: Path): String = sh("cat \"$1\"", file.toString()).events().text(ChildStream.Stdout)
}
