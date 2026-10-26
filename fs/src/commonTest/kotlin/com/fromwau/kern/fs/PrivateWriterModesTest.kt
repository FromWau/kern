package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class PrivateWriterModesTest {
    private val dir = newTempDir()
    private val home = (dir / "home").also { createDirectoriesRaw(it) }
    private val shared = sharedFolderBits(dir / "probe")
    private val ownerOnly = 0b111_000_000

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    /** The app's folders under [home], with every XDG variable unset. */
    private fun app(): AppDirs = BaseDirs
        .fromXdg(mapOf("HOME" to home.toString()), accountHome = null)
        .assertSuccess()
        .forApp("app")
        .assertSuccess()

    @Test
    fun `a private writer makes its root 700 and the missing levels above it as a shared writer makes them`() {
        val root = home / ".local" / "share" / "app"

        root.privateWriter().assertSuccess()

        (home / ".local").assertPermissions(shared)
        (home / ".local" / "share").assertPermissions(shared)
        root.assertPermissions(ownerOnly)
    }

    @Test
    fun `a child private writer under a shared writer makes the levels on the way as its parent and its root 700`() {
        val data = (home / "data").writer().assertSuccess()

        data.privateWriter("accounts/alice/keys").assertSuccess()

        (home / "data" / "accounts").assertPermissions(shared)
        (home / "data" / "accounts" / "alice").assertPermissions(shared)
        (home / "data" / "accounts" / "alice" / "keys").assertPermissions(ownerOnly)
    }

    @Test
    fun `a child private writer makes the same modes as a private writer on that path`() {
        val child = (home / "child").writer().assertSuccess()
        val direct = home / "direct"
        createDirectoriesRaw(direct)

        child.privateWriter("accounts/alice/keys").assertSuccess()
        (direct / "accounts" / "alice" / "keys").privateWriter().assertSuccess()

        val levels = listOf("accounts", "accounts/alice", "accounts/alice/keys")
        assertEquals(
            levels.map { Path(direct, it).permissions().assertSuccess() },
            levels.map { Path(home / "child", it).permissions().assertSuccess() },
        )
    }

    @Test
    fun `a child private writer under a private writer makes every missing level 700`() {
        val secrets = (home / "secrets").privateWriter().assertSuccess()

        secrets.privateWriter("a/b").assertSuccess()

        (home / "secrets" / "a").assertPermissions(ownerOnly)
        (home / "secrets" / "a" / "b").assertPermissions(ownerOnly)
    }

    @Test
    fun `a child shared writer under a private writer makes every missing level 700 as its parent makes folders`() {
        val secrets = (home / "secrets").privateWriter().assertSuccess()

        val child = secrets.writer("a/b").assertSuccess()

        (home / "secrets" / "a").assertPermissions(ownerOnly)
        (home / "secrets" / "a" / "b").assertPermissions(ownerOnly)
        assertEquals(false, child.isPrivate)
    }

    @Test
    fun `a private writer makes each folder below its root 700`() {
        val writer = (home / "secrets").privateWriter().assertSuccess()

        writer.writeText("a/b/token", "t").assertSuccess()

        (home / "secrets" / "a").assertPermissions(ownerOnly)
        (home / "secrets" / "a" / "b").assertPermissions(ownerOnly)
    }

    @Test
    fun `a private writer leaves a parent already there as it is`() {
        val parent = (home / "parent").also { createDirectoriesRaw(it) }
        parent.chmod(0b111_101_000).assertSuccess()

        (parent / "app").privateWriter().assertSuccess()

        parent.assertPermissions(0b111_101_000)
        (parent / "app").assertPermissions(ownerOnly)
    }

    @Test
    fun `singleInstance in a fresh home makes the state levels above the app's folder 755 and that folder 700`() {
        val app = app()

        app.singleInstance("mirror").assertSuccess().close()

        (home / ".local").assertPermissions(shared)
        (home / ".local" / "state").assertPermissions(shared)
        app.state.assertPermissions(ownerOnly)
    }

    @Test
    fun `withRunTemp makes the levels above the run's folder 755`() {
        val app = BaseDirs.under(dir / "base").forApp("app").assertSuccess()

        app.withRunTemp { }.assertSuccess()

        (dir / "base").assertPermissions(shared)
        (dir / "base" / "tmp").assertPermissions(shared)
        (dir / "base" / "tmp" / "app").assertPermissions(shared)
    }

    @Test
    fun `withRunTemp makes the run's folder 700`() {
        val app = BaseDirs.under(dir / "base").forApp("app").assertSuccess()

        app.withRunTemp { writer -> writer.root.assertPermissions(ownerOnly) }.assertSuccess()
    }
}
