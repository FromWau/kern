package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseDirsFactoryJvmTest {
    private fun envOf(vararg variables: Pair<String, String>): (String) -> String? = mapOf(*variables)::get

    @Test
    fun `macOS and Darwin get the macOS rules`() {
        val caches = Path("/Users/me/Library/Caches")

        assertEquals(caches, baseDirFor("Mac OS X", envOf(), userHome = "/Users/me").getOrNull()?.cacheHome)
        assertEquals(caches, baseDirFor("Darwin", envOf(), userHome = "/Users/me").getOrNull()?.cacheHome)
    }

    @Test
    fun `Windows gets the Windows rules and anything else the XDG rules`() {
        val env = envOf("HOME" to "/home/me", "APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")

        assertEquals(Path("/local"), baseDirFor("Windows 11", env, userHome = "/profile").getOrNull()?.cacheHome)
        assertEquals(Path("/home/me/.cache"), baseDirFor("Linux", env, userHome = null).getOrNull()?.cacheHome)
    }

    @Test
    fun `a blank variable counts as unset`() {
        val env = envOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to " ")

        assertEquals(Path("/home/me/.config"), baseDirFor("Linux", env, userHome = null).getOrNull()?.configHome)
    }

    @Test
    fun `linux uses HOME and the XDG defaults under it`() {
        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/home/me/.config"),
            dataHome = Path("/home/me/.local/share"),
            stateHome = Path("/home/me/.local/state"),
            cacheHome = Path("/home/me/.cache"),
            tempHome = Path("/tmp"),
        )

        assertEquals(Ok(expected), linuxBaseDir(envOf("HOME" to "/home/me"), userHome = "/home/account"))
    }

    @Test
    fun `linux takes each root from its XDG variable`() {
        val env = envOf(
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

        assertEquals(Ok(expected), linuxBaseDir(env, userHome = "/home/account"))
    }

    @Test
    fun `linux falls back to user home when HOME is unset`() {
        assertEquals(Path("/home/account"), linuxBaseDir(envOf(), userHome = "/home/account").getOrNull()?.home)
    }

    @Test
    fun `linux without any home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), linuxBaseDir(envOf(), userHome = null))
    }

    @Test
    fun `windows keeps config data and state in APPDATA and cache in LOCALAPPDATA`() {
        val env = envOf("APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")
        val expected = BaseDirs(
            home = Path("/profile"),
            configHome = Path("/roaming"),
            dataHome = Path("/roaming"),
            stateHome = Path("/roaming"),
            cacheHome = Path("/local"),
            tempHome = Path("/temp"),
        )

        assertEquals(Ok(expected), windowsBaseDir(env, userHome = "/profile"))
    }

    @Test
    fun `windows names the root it could not resolve`() {
        val all = mapOf("APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")

        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), windowsBaseDir(all::get, userHome = null))
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Config)),
            windowsBaseDir((all - "APPDATA")::get, userHome = "/profile"),
        )
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Cache)),
            windowsBaseDir((all - "LOCALAPPDATA")::get, userHome = "/profile"),
        )
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Temp)),
            windowsBaseDir((all - "TEMP")::get, userHome = "/profile"),
        )
    }

    @Test
    fun `macos keeps config data and state in Application Support and cache in Caches`() {
        val support = Path("/Users/me/Library/Application Support")
        val expected = BaseDirs(
            home = Path("/Users/me"),
            configHome = support,
            dataHome = support,
            stateHome = support,
            cacheHome = Path("/Users/me/Library/Caches"),
            tempHome = Path("/tmp"),
        )

        assertEquals(Ok(expected), appleBaseDir(envOf(), userHome = "/Users/me"))
    }

    @Test
    fun `macos without a home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), appleBaseDir(envOf(), userHome = null))
    }

    @Test
    fun `TMPDIR is the temp root on Linux and macOS`() {
        val env = envOf("HOME" to "/home/me", "TMPDIR" to "/run/user/1000/tmp")
        val expected = Path("/run/user/1000/tmp")

        assertEquals(expected, baseDirFor("Linux", env, userHome = null).getOrNull()?.tempHome)
        assertEquals(expected, baseDirFor("Mac OS X", env, userHome = "/Users/me").getOrNull()?.tempHome)
    }

    @Test
    fun `a blank or missing TMPDIR leaves the temp root at slash tmp`() {
        val blank = envOf("HOME" to "/home/me", "TMPDIR" to " ")

        assertEquals(Path("/tmp"), baseDirFor("Linux", blank, userHome = null).getOrNull()?.tempHome)
        assertEquals(Path("/tmp"), baseDirFor("Mac OS X", blank, userHome = "/Users/me").getOrNull()?.tempHome)
        assertEquals(Path("/tmp"), baseDirFor("Linux", envOf("HOME" to "/home/me"), null).getOrNull()?.tempHome)
    }
}
