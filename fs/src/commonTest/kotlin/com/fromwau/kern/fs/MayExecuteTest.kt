package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MayExecuteTest {
    private val dir = newTempDir()
    private val file = dir / "tool"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a file written executable may be run`() {
        file.writeText("#!/bin/sh\n", executable = true).assertSuccess()

        assertEquals(Ok(true), file.mayExecute())
    }

    @Test
    fun `a regular file without an execute bit may not be run where files have one`() {
        file.writeText("#!/bin/sh\n").assertSuccess()

        // Windows has no execute bit, so any regular file there may run.
        assertEquals(Ok(!hasPermissions()), file.mayExecute())
    }

    @Test
    fun `a missing file is NotFound`() {
        assertEquals(Err(FileError.NotFound(file)), file.mayExecute())
    }

    @Test
    fun `a folder is no file to run though this user may enter it`() {
        assertEquals(Ok(false), dir.mayExecute())
    }

    @Test
    fun `a link to an executable file may be run`() {
        file.writeText("#!/bin/sh\n", executable = true).assertSuccess()
        val link = dir / "link"
        if (link.createSymlink(file) !is Result.Success) return

        assertEquals(Ok(true), link.mayExecute())
    }

    // Permissions.anyExecute says yes to 001, but the system weighs the owner's own bits for the owner. Root may run
    // any file with an execute bit, so a run as root has nothing to tell apart here.
    @Test
    fun `a file only others may run may not be run by its owner`() {
        if (!hasPermissions() || currentUserId() == 0L) return
        file.writeText("#!/bin/sh\n").assertSuccess()
        file.chmod(0b000_000_001).assertSuccess()
        assertEquals(true, file.permissions().assertSuccess()?.anyExecute)

        assertEquals(Ok(false), file.mayExecute())
    }

    // A noexec mount refuses to run any file on it, whatever its mode says. This one needs a file already there whose
    // bits let every class run it, as on a FAT /boot mounted noexec, and passes without asking where none is found.
    @Test
    fun `a file on a noexec mount may not be run whatever its mode says`() {
        val runnable = runnableLookingFileOnNoexecMount() ?: return

        assertEquals(Ok(false), runnable.mayExecute(), "$runnable")
    }

    /** A regular file on a mount listed `noexec` in `/proc/self/mounts`, with a mode that lets everyone run it. */
    private fun runnableLookingFileOnNoexecMount(): Path? {
        val mounts = Path("/proc/self/mounts").readText(maxBytes = 1_048_576).getOrNull() ?: return null
        val noexec = mounts
            .lineSequence()
            .map { it.split(' ') }
            .filter { fields -> fields.size > 3 && "noexec" in fields[3].split(',') && '\\' !in fields[1] }
            .map { fields -> Path(fields[1]) }
        return noexec
            .flatMap { mount -> mount.list().getOrNull().orEmpty() }
            .firstOrNull { entry ->
                val info = entry.info().getOrNull()
                info?.type == FileType.Regular && (info.permissions?.bits ?: 0) and ALL_EXECUTE == ALL_EXECUTE
            }
    }

    private companion object {
        const val ALL_EXECUTE = 0b001_001_001
    }
}
