package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.NameProblem
import com.fromwau.kern.fs.RelativePathProblem
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.fs.writer
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class WriterAwaitLockTest {
    private val dir = SystemTemporaryDirectory / "kern-fs-coroutines-${Uuid.random()}"
    private val rootPath = (dir / "root").also { SystemFileSystem.createDirectories(it) }
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        val _ = dir.deleteRecursively()
    }

    @Test
    fun `awaitLock on a writer waits by suspending until another holder lets go`() = runTest {
        val held = writer.tryLock("locks/.app.lock").assertSuccess()
        launch {
            delay(100)
            held.close()
        }

        assertEquals(Ok("got it"), writer.awaitLock("locks/.app.lock", wait = 500.milliseconds) { "got it" })
    }

    @Test
    fun `awaitLock on a writer gives up as LockBusy`() = runTest {
        writer.tryLock(".app.lock").assertSuccess().use {
            writer.awaitLock(".app.lock", wait = Duration.ZERO) { "ran" }.assertError<FileError.LockBusy>()
        }
    }

    @Test
    fun `awaitLock on a writer of a bad name runs nothing`() = runTest {
        var ran = false
        val problem = RelativePathProblem.BadName(0, "..", NameProblem.DotDot)

        assertEquals(Err(FileError.InvalidRelativePath(root, "../x", problem)), writer.awaitLock("../x") { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `awaitLock on a writer through a link leading out is Escapes`() = runTest {
        SystemFileSystem.createDirectories(dir / "outside")
        (rootPath / "sub").createSymlink(Path("../outside")).assertSuccess()

        val escaped = writer.awaitLock("sub/.app.lock") { "ran" }.assertError<FileError.Escapes>()
        assertEquals(root / "sub" / ".app.lock", escaped.path)
        assertEquals(emptyList(), SystemFileSystem.list(dir / "outside"))
    }

    @Test
    fun `awaitLock on a writer whose root was replaced is FolderReplaced`() = runTest {
        SystemFileSystem.atomicMove(rootPath, dir / "moved")
        SystemFileSystem.createDirectories(rootPath)

        assertEquals(Err(FileError.FolderReplaced(root)), writer.awaitLock(".app.lock") { "ran" })
        assertEquals(emptyList(), SystemFileSystem.list(rootPath))
    }
}
