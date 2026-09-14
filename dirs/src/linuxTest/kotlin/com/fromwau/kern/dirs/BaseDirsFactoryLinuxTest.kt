package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class BaseDirsFactoryLinuxTest {
    private val variables =
        listOf("HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "XDG_CACHE_HOME", "TMPDIR")
    private val saved = variables.associateWith { getenv(it)?.toKString() }

    @AfterTest
    fun restoreEnvironment() {
        saved.forEach { (name, value) -> setOrUnset(name, value) }
    }

    /** Leaves exactly [values] set among [variables]. */
    private fun environment(vararg values: Pair<String, String>) {
        val wanted = values.toMap()
        variables.forEach { setOrUnset(it, wanted[it]) }
    }

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
    fun `a blank XDG variable counts as unset`() {
        environment("HOME" to "/home/me", "XDG_CONFIG_HOME" to " ")

        assertEquals(Path("/home/me/.config"), BaseDirsFactory().create().getOrNull()?.configHome)
    }

    @Test
    fun `without HOME it is UnableToResolve Home`() {
        environment()

        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), BaseDirsFactory().create())
    }

    @Test
    fun `a blank HOME is UnableToResolve Home`() {
        environment("HOME" to " ")

        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), BaseDirsFactory().create())
    }

    @Test
    fun `TMPDIR is the temp root and a blank one leaves it at slash tmp`() {
        environment("HOME" to "/home/me", "TMPDIR" to "/run/user/1000/tmp")
        assertEquals(Path("/run/user/1000/tmp"), BaseDirsFactory().create().getOrNull()?.tempHome)

        environment("HOME" to "/home/me", "TMPDIR" to " ")
        assertEquals(Path("/tmp"), BaseDirsFactory().create().getOrNull()?.tempHome)
    }
}
