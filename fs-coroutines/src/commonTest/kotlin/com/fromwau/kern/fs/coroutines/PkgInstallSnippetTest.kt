package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.Writer
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.writer
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.result
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PkgInstallSnippetTest {
    private val scratch = scratchDir()
    private val cache: Writer = (scratch / "cache").writer().assertSuccess()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively().assertSuccess()
    }

    private data class Pkg(val name: String)

    private sealed interface PkgError : ResultError {
        data class File(val error: FileError) : PkgError

        data object BadManifest : PkgError
    }

    private var manifest = "tool"
    private var lockHeldDuringInstall = false

    private fun install(manifest: String): Result<Pkg, PkgError> {
        lockHeldDuringInstall = when (val tried = cache.tryLock("pkg.lock")) {
            is Result.Success -> {
                tried.value.close()
                false
            }
            is Result.Error -> tried.error is FileError.LockBusy
        }
        return if (manifest.isBlank()) Err(PkgError.BadManifest) else Ok(Pkg(manifest))
    }

    /** The install under a lock from fs-coroutines/README.md, as written there. */
    private suspend fun installLocked(): Result<Pkg, PkgError> {
        val pkg: Result<Pkg, PkgError> = result {
            cache.awaitLock("pkg.lock") { install(manifest).bind() }.bind(PkgError::File)
        }
        return pkg
    }

    @Test
    fun `the install under a lock answers the package and holds the lock while it installs`() = runTest {
        assertEquals(Ok(Pkg("tool")), installLocked())
        assertTrue(lockHeldDuringInstall)
    }

    @Test
    fun `the install under a lock passes the install's own error on as it is and releases the lock`() = runTest {
        manifest = " "

        assertEquals(Err(PkgError.BadManifest), installLocked())
        cache.tryLock("pkg.lock").assertSuccess().close()
    }

    @Test
    fun `the install under a lock wraps a busy lock in the app's File case`() = runTest {
        cache.tryLock("pkg.lock").assertSuccess().use {
            val failure = assertIs<Result.Error<PkgError>>(installLocked()).error

            assertIs<FileError.LockBusy>(assertIs<PkgError.File>(failure).error)
        }
    }
}
