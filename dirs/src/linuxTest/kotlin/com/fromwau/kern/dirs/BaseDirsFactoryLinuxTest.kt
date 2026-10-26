package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.getenv
import platform.posix.getpwuid
import platform.posix.getuid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.rmdir
import platform.posix.setenv
import platform.posix.symlink
import platform.posix.unlink
import platform.posix.unsetenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class)
class BaseDirsFactoryLinuxTest {
    private val variables = listOf(
        "HOME",
        "XDG_CONFIG_HOME",
        "XDG_DATA_HOME",
        "XDG_STATE_HOME",
        "XDG_CACHE_HOME",
        "XDG_RUNTIME_DIR",
        "TMPDIR",
    )
    private val saved = variables.associateWith { getenv(it)?.toKString() }
    private val scratch = "${getenv("TMPDIR")?.toKString() ?: "/tmp"}/kern-dirs-${Uuid.random()}".also {
        mkdir(it, 0b111_000_000.convert())
    }
    private val made = mutableListOf<String>()
    private val madeEntries = mutableListOf<String>()

    @AfterTest
    fun restoreEnvironment() {
        saved.forEach { (name, value) -> setOrUnset(name, value) }
    }

    /** Leaves exactly [values] set among [variables]. */
    private fun environment(vararg values: Pair<String, String>) {
        val wanted = values.toMap()
        variables.forEach { setOrUnset(it, wanted[it]) }
    }

    private fun accountHome(): String = assertNotNull(getpwuid(getuid())).pointed.pw_dir!!.toKString()

    private fun setOrUnset(
        name: String,
        value: String?,
    ) {
        if (value == null) unsetenv(name) else setenv(name, value, 1)
    }

    @Test
    fun `HOME and the XDG defaults under it`() {
        environment("HOME" to "/home/me")
        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/home/me/.config"),
            dataHome = Path("/home/me/.local/share"),
            stateHome = Path("/home/me/.local/state"),
            cacheHome = Path("/home/me/.cache"),
            tempHome = Path("/tmp"),
        )

        assertEquals(Ok(expected), BaseDirsFactory().create())
    }

    /** A new folder of this user's with [mode], removed again after the test. */
    private fun folder(
        name: String,
        mode: Int,
    ): String {
        val path = "$scratch/$name"
        assertEquals(0, mkdir(path, mode.convert()))
        // mkdir applies the umask, so the mode is set again on the folder made.
        assertEquals(0, chmod(path, mode.convert()))
        made += path
        return path
    }

    /** A symlink at [name] holding [target], removed again after the test. */
    private fun link(
        name: String,
        target: String,
    ): String {
        val path = "$scratch/$name"
        assertEquals(0, symlink(target, path))
        madeEntries += path
        return path
    }

    /** An empty regular file of this user's with no group or other bits, removed again after the test. */
    private fun file(name: String): String {
        val path = "$scratch/$name"
        val descriptor = open(path, O_CREAT or O_WRONLY or O_EXCL, 0b110_000_000)
        assertTrue(descriptor >= 0)
        close(descriptor)
        madeEntries += path
        return path
    }

    @AfterTest
    fun removeFolders() {
        madeEntries.forEach { unlink(it) }
        made.forEach { rmdir(it) }
        rmdir(scratch)
    }

    @Test
    fun `fromXdg with every variable set takes each root and the private runtime folder`() {
        val private = folder("private", 0b111_000_000)
        val env = mapOf(
            "HOME" to "/home/me",
            "TMPDIR" to "/scratch/tmp",
            "XDG_CONFIG_HOME" to "/x/config",
            "XDG_DATA_HOME" to "/x/data",
            "XDG_STATE_HOME" to "/x/state",
            "XDG_CACHE_HOME" to "/x/cache",
            "XDG_RUNTIME_DIR" to private,
        )

        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/x/config"),
            dataHome = Path("/x/data"),
            stateHome = Path("/x/state"),
            cacheHome = Path("/x/cache"),
            tempHome = Path("/scratch/tmp"),
            runtimeHome = Path(private),
        )
        assertEquals(Ok(expected), BaseDirs.fromXdg(env, accountHome = Path("/home/account"), strict = true))
    }

    @Test
    fun `the runtime root comes from XDG_RUNTIME_DIR naming a folder of this user's with no group or other bits`() {
        val private = folder("private", 0b111_000_000)
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to private)

        assertEquals(Path(private), BaseDirsFactory().create().assertSuccess().runtimeHome)
    }

    @Test
    fun `an XDG_RUNTIME_DIR naming a link to a private folder is taken as the link`() {
        val linked = link("link", folder("private", 0b111_000_000))
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to linked)

        assertEquals(Path(linked), BaseDirsFactory().create().assertSuccess().runtimeHome)
    }

    @Test
    fun `an XDG_RUNTIME_DIR naming a missing or open folder or a file is ignored as NotPrivateFolder`() {
        for (runtime in listOf("$scratch/missing", folder("open", 0b111_101_101), file("file"))) {
            environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to runtime)

            val dirs = BaseDirsFactory().create().assertSuccess()

            assertNull(dirs.runtimeHome)
            assertEquals(
                listOf(IgnoredVariable("XDG_RUNTIME_DIR", runtime, IgnoredBecause.NotPrivateFolder)),
                dirs.ignored,
            )
            assertEquals(Err(DirsError.NotPrivate("XDG_RUNTIME_DIR", runtime)), BaseDirsFactory(strict = true).create())
        }
    }

    /** The README's check for an app that never uses runtime, as written there. */
    private fun relativeVariableCheck(): BaseDirs {
        val base = BaseDirsFactory().create().getOrElse { error("no folders for this app: $it") }
        val refused = base.ignored.firstOrNull { it.name != "XDG_RUNTIME_DIR" }?.toError()
        if (refused != null) error("no folders for this app: $refused")
        return base
    }

    @Test
    fun `the check for an app without runtime passes a missing XDG_RUNTIME_DIR and refuses a relative variable`() {
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "$scratch/missing")

        assertNull(relativeVariableCheck().runtimeHome)

        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "$scratch/missing", "XDG_CACHE_HOME" to "cache")

        val refused = assertFailsWith<IllegalStateException> { relativeVariableCheck() }
        assertEquals("no folders for this app: ${DirsError.NotAbsolute("XDG_CACHE_HOME", "cache")}", refused.message)
    }

    @Test
    fun `the check for an app without runtime passes a blank XDG_RUNTIME_DIR`() {
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to " ")

        assertNull(relativeVariableCheck().runtimeHome)
    }

    @Test
    fun `the check for an app without runtime refuses a blank variable as strict does`() {
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "$scratch/missing", "XDG_CACHE_HOME" to " ")

        val refused = assertFailsWith<IllegalStateException> { relativeVariableCheck() }

        assertEquals("no folders for this app: ${DirsError.Blank("XDG_CACHE_HOME", " ")}", refused.message)
    }

    @Test
    fun `each root comes from its XDG variable`() {
        environment(
            "HOME" to "/home/me",
            "XDG_CONFIG_HOME" to "/xdg/config",
            "XDG_DATA_HOME" to "/xdg/data",
            "XDG_STATE_HOME" to "/xdg/state",
            "XDG_CACHE_HOME" to "/xdg/cache",
        )
        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/xdg/config"),
            dataHome = Path("/xdg/data"),
            stateHome = Path("/xdg/state"),
            cacheHome = Path("/xdg/cache"),
            tempHome = Path("/tmp"),
        )

        assertEquals(Ok(expected), BaseDirsFactory().create())
    }

    @Test
    fun `a blank XDG variable is listed as Blank and counts as unset`() {
        environment("HOME" to "/home/me", "XDG_CONFIG_HOME" to " ")

        val base = BaseDirsFactory().create().assertSuccess()

        assertEquals(Path("/home/me/.config"), base.configHome)
        assertEquals(listOf(IgnoredVariable("XDG_CONFIG_HOME", " ", IgnoredBecause.Blank)), base.ignored)
    }

    @Test
    fun `strict refuses a blank variable as Blank`() {
        environment("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "")

        assertEquals(Err(DirsError.Blank("XDG_RUNTIME_DIR", "")), BaseDirsFactory(strict = true).create())
    }

    @Test
    fun `without HOME the home is the account's`() {
        environment()

        assertEquals(Path(accountHome()), BaseDirsFactory().create().assertSuccess().home)
    }

    @Test
    fun `a blank or relative HOME gives the account's home`() {
        for (home in listOf(" ", "home/me")) {
            environment("HOME" to home)

            assertEquals(Path(accountHome(), ".config"), BaseDirsFactory().create().assertSuccess().configHome)
        }
    }

    @Test
    fun `a relative variable counts as unset`() {
        environment(
            "HOME" to "/home/me",
            "XDG_CONFIG_HOME" to "config",
            "XDG_DATA_HOME" to "./data",
            "XDG_STATE_HOME" to "state",
            "XDG_CACHE_HOME" to "~/.cache",
            "XDG_RUNTIME_DIR" to "run",
            "TMPDIR" to "tmp",
        )
        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/home/me/.config"),
            dataHome = Path("/home/me/.local/share"),
            stateHome = Path("/home/me/.local/state"),
            cacheHome = Path("/home/me/.cache"),
            tempHome = Path("/tmp"),
            ignored = listOf(
                IgnoredVariable("TMPDIR", "tmp", IgnoredBecause.NotAbsolute),
                IgnoredVariable("XDG_CONFIG_HOME", "config", IgnoredBecause.NotAbsolute),
                IgnoredVariable("XDG_DATA_HOME", "./data", IgnoredBecause.NotAbsolute),
                IgnoredVariable("XDG_STATE_HOME", "state", IgnoredBecause.NotAbsolute),
                IgnoredVariable("XDG_CACHE_HOME", "~/.cache", IgnoredBecause.NotAbsolute),
                IgnoredVariable("XDG_RUNTIME_DIR", "run", IgnoredBecause.NotAbsolute),
            ),
        )

        assertEquals(Ok(expected), BaseDirsFactory().create())
    }

    @Test
    fun `a relative HOME and XDG_CONFIG_HOME are listed as ignored with their values`() {
        environment("HOME" to "rel", "XDG_CONFIG_HOME" to "cfg", "XDG_DATA_HOME" to "/data", "XDG_STATE_HOME" to " ")

        val ignored = BaseDirsFactory().create().assertSuccess().ignored

        val expected = listOf(
            IgnoredVariable("HOME", "rel", IgnoredBecause.NotAbsolute),
            IgnoredVariable("XDG_CONFIG_HOME", "cfg", IgnoredBecause.NotAbsolute),
            IgnoredVariable("XDG_STATE_HOME", " ", IgnoredBecause.Blank),
        )
        assertEquals(expected, ignored)
    }

    @Test
    fun `TMPDIR is the temp root`() {
        environment("HOME" to "/home/me", "TMPDIR" to "/run/user/1000/tmp")

        assertEquals(Path("/run/user/1000/tmp"), BaseDirsFactory().create().assertSuccess().tempHome)
    }

    @Test
    fun `a blank TMPDIR leaves the temp root at slash tmp`() {
        environment("HOME" to "/home/me", "TMPDIR" to " ")

        assertEquals(Path("/tmp"), BaseDirsFactory().create().assertSuccess().tempHome)
    }

    @Test
    fun `trailing separators on HOME are dropped`() {
        for (home in listOf("/x/home/", "/x/home//")) {
            environment("HOME" to home)
            val roots = BaseDirsFactory().create().assertSuccess()

            assertEquals(Path("/x/home"), roots.home)
            assertEquals(Path("/x/home/.config"), roots.configHome)
            assertEquals(Path("/x/home/.local/share"), roots.dataHome)
        }
    }

    @Test
    fun `a HOME of slash keeps the roots directly under it`() {
        environment("HOME" to "/")
        val roots = BaseDirsFactory().create().assertSuccess()

        assertEquals(Path("/"), roots.home)
        assertEquals(Path("/.config"), roots.configHome)
    }

    @Test
    fun `trailing separators on an XDG variable are dropped`() {
        for (config in listOf("/x/config/", "/x/config//")) {
            environment("HOME" to "/home/me", "XDG_CONFIG_HOME" to config)

            assertEquals(Path("/x/config"), BaseDirsFactory().create().assertSuccess().configHome)
        }
    }

    @Test
    fun `a strict factory refuses a relative variable as NotAbsolute`() {
        environment("HOME" to "/home/me", "XDG_CONFIG_HOME" to "cfg")

        assertEquals(Err(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg")), BaseDirsFactory(strict = true).create())
        assertEquals(
            listOf(IgnoredVariable("XDG_CONFIG_HOME", "cfg", IgnoredBecause.NotAbsolute)),
            BaseDirsFactory().create().assertSuccess().ignored,
        )
    }

    @Test
    fun `current gives the roots a factory gives and a strict one refuses a relative variable`() {
        environment("HOME" to "/home/me", "XDG_CONFIG_HOME" to "cfg")

        assertEquals(BaseDirsFactory().create(), BaseDirs.current())
        assertEquals(Err(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg")), BaseDirs.current(strict = true))
    }
}
