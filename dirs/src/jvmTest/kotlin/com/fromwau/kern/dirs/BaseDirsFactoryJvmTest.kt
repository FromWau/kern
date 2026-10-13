package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BaseDirsFactoryJvmTest {
    private fun envOf(vararg variables: Pair<String, String>): (String) -> String? = mapOf(*variables)::get

    @Test
    fun `macOS and Darwin get the macOS rules`() {
        val caches = Path("/Users/me/Library/Caches")

        assertEquals(caches, baseDirFor("Mac OS X", envOf(), userHome = "/Users/me").assertSuccess().cacheHome)
        assertEquals(caches, baseDirFor("Darwin", envOf(), userHome = "/Users/me").assertSuccess().cacheHome)
    }

    @Test
    fun `Windows gets the Windows rules and anything else the XDG rules`() {
        val env = envOf("HOME" to "/home/me", "APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")

        assertEquals(Path("/local"), baseDirFor("Windows 11", env, userHome = "/profile").assertSuccess().cacheHome)
        assertEquals(Path("/home/me/.cache"), baseDirFor("Linux", env, userHome = null).assertSuccess().cacheHome)
    }

    @Test
    fun `a user home that is not an absolute path counts as unset`() {
        val unresolved = Err(DirsError.UnableToResolve(DirKind.Home))

        for (userHome in listOf("", " ", "?", "relative/home")) {
            assertEquals(unresolved, baseDirFor("Linux", envOf(), userHome))
            assertEquals(unresolved, baseDirFor("Mac OS X", envOf(), userHome))
        }
        assertEquals(Path("/home/me"), baseDirFor("Linux", envOf("HOME" to "/home/me"), "?").assertSuccess().home)
    }

    @Test
    fun `a blank variable counts as unset`() {
        val env = envOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to " ")

        assertEquals(Path("/home/me/.config"), baseDirFor("Linux", env, userHome = null).assertSuccess().configHome)
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
    fun `linux takes the runtime root from XDG_RUNTIME_DIR and has none without it`() {
        val env = envOf("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "/run/user/1000")

        assertEquals(Path("/run/user/1000"), linuxBaseDir(env, userHome = null).assertSuccess().runtimeHome)
        assertNull(linuxBaseDir(envOf("HOME" to "/home/me"), userHome = null).assertSuccess().runtimeHome)
    }

    @Test
    fun `only linux has a runtime root`() {
        val env = envOf(
            "HOME" to "/home/me",
            "XDG_RUNTIME_DIR" to "/run/user/1000",
            "APPDATA" to "/roaming",
            "LOCALAPPDATA" to "/local",
            "TEMP" to "/temp",
        )

        assertNull(baseDirFor("Windows 11", env, userHome = "/profile").assertSuccess().runtimeHome)
        assertNull(baseDirFor("Mac OS X", env, userHome = "/Users/me").assertSuccess().runtimeHome)
    }

    @Test
    fun `linux falls back to user home when HOME is unset`() {
        assertEquals(Path("/home/account"), linuxBaseDir(envOf(), userHome = "/home/account").assertSuccess().home)
    }

    @Test
    fun `linux without any home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), linuxBaseDir(envOf(), userHome = null))
    }

    @Test
    fun `windows keeps config and data in APPDATA and state and cache in LOCALAPPDATA`() {
        val env = envOf("APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")
        val expected = BaseDirs(
            home = Path("/profile"),
            configHome = Path("/roaming"),
            dataHome = Path("/roaming"),
            stateHome = Path("/local"),
            cacheHome = Path("/local"),
            tempHome = Path("/temp"),
            naming = AppNaming.Windows,
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
            naming = AppNaming.Apple,
        )

        assertEquals(Ok(expected), appleBaseDir(envOf(), userHome = "/Users/me"))
    }

    @Test
    fun `macos with the Xdg layout puts the XDG roots under the account home`() {
        val expected = BaseDirs(
            home = Path("/Users/me"),
            configHome = Path("/Users/me/.config"),
            dataHome = Path("/Users/me/.local/share"),
            stateHome = Path("/Users/me/.local/state"),
            cacheHome = Path("/Users/me/.cache"),
            tempHome = Path("/tmp"),
        )

        assertEquals(Ok(expected), baseDirFor("Mac OS X", envOf(), userHome = "/Users/me", DirLayout.Xdg))
    }

    @Test
    fun `macos with the Xdg layout takes each root from its XDG variable`() {
        val env = envOf(
            "XDG_CONFIG_HOME" to "/xdg/config",
            "XDG_DATA_HOME" to "/xdg/data",
            "XDG_STATE_HOME" to "/xdg/state",
            "XDG_CACHE_HOME" to "/xdg/cache",
        )
        val dirs = baseDirFor("Mac OS X", env, userHome = "/Users/me", DirLayout.Xdg).assertSuccess()

        assertEquals(
            listOf(Path("/xdg/config"), Path("/xdg/data"), Path("/xdg/state"), Path("/xdg/cache")),
            listOf(dirs.configHome, dirs.dataHome, dirs.stateHome, dirs.cacheHome),
        )
    }

    @Test
    fun `macos with the Xdg layout puts the roots under HOME as Linux does`() {
        val dirs = baseDirFor("Mac OS X", envOf("HOME" to "/tmp/sandbox"), userHome = "/Users/me", DirLayout.Xdg)

        assertEquals(Path("/tmp/sandbox/.config"), dirs.assertSuccess().configHome)
    }

    @Test
    fun `the layout changes nothing on Linux and Windows`() {
        val env = envOf("HOME" to "/home/me", "APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")

        for (os in listOf("Linux", "Windows 11")) {
            assertEquals(baseDirFor(os, env, userHome = "/home/me"), baseDirFor(os, env, "/home/me", DirLayout.Xdg))
        }
    }

    @Test
    fun `each platform names an app's folder its own way`() {
        val env = envOf("HOME" to "/home/me", "APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")
        val naming = { os: String, layout: DirLayout -> baseDirFor(os, env, "/home/me", layout).assertSuccess().naming }

        assertEquals(AppNaming.Xdg, naming("Linux", DirLayout.Native))
        assertEquals(AppNaming.Windows, naming("Windows 11", DirLayout.Native))
        assertEquals(AppNaming.Apple, naming("Mac OS X", DirLayout.Native))
        assertEquals(AppNaming.Xdg, naming("Mac OS X", DirLayout.Xdg))
    }

    @Test
    fun `macos without a home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), appleBaseDir(envOf(), userHome = null))
    }

    @Test
    fun `TMPDIR is the temp root on Linux and macOS`() {
        val env = envOf("HOME" to "/home/me", "TMPDIR" to "/run/user/1000/tmp")
        val expected = Path("/run/user/1000/tmp")

        assertEquals(expected, baseDirFor("Linux", env, userHome = null).assertSuccess().tempHome)
        assertEquals(expected, baseDirFor("Mac OS X", env, userHome = "/Users/me").assertSuccess().tempHome)
    }

    @Test
    fun `a blank or missing TMPDIR leaves the temp root at slash tmp`() {
        val blank = envOf("HOME" to "/home/me", "TMPDIR" to " ")

        assertEquals(Path("/tmp"), baseDirFor("Linux", blank, userHome = null).assertSuccess().tempHome)
        assertEquals(Path("/tmp"), baseDirFor("Mac OS X", blank, userHome = "/Users/me").assertSuccess().tempHome)
        assertEquals(Path("/tmp"), baseDirFor("Linux", envOf("HOME" to "/home/me"), null).assertSuccess().tempHome)
    }
}
