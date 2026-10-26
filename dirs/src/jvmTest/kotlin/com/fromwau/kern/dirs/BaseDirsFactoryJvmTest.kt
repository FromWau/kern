package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseDirsFactoryJvmTest {
    private fun envOf(vararg variables: Pair<String, String>): (String) -> String? = mapOf(*variables)::get

    private fun notAbsolute(
        name: String,
        value: String,
    ) = IgnoredVariable(name, value, IgnoredBecause.NotAbsolute)

    /** Linux's roots from [env], taking every runtime folder as private. */
    private fun linuxWith(
        env: (String) -> String?,
        userHome: () -> String?,
    ) = linuxBaseDir(env, userHome, strict = false, isPrivateFolder = { true })

    private fun mode(bits: String) = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(bits))

    @Test
    fun `current gives the roots a factory with the default layout gives`() {
        assertEquals(BaseDirsFactory().create(), BaseDirs.current())
        assertEquals(BaseDirsFactory(strict = true).create(), BaseDirs.current(strict = true))
    }

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
    fun `a blank variable is listed as Blank and counts as unset`() {
        val env = envOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to " ")

        val base = baseDirFor("Linux", env, userHome = null).assertSuccess()

        assertEquals(Path("/home/me/.config"), base.configHome)
        assertEquals(listOf(IgnoredVariable("XDG_CONFIG_HOME", " ", IgnoredBecause.Blank)), base.ignored)
    }

    @Test
    fun `a relative variable counts as unset`() {
        val env = envOf(
            "HOME" to "/home/me",
            "XDG_CONFIG_HOME" to "config",
            "XDG_DATA_HOME" to "./data",
            "XDG_STATE_HOME" to "state",
            "XDG_CACHE_HOME" to "~/.cache",
            "XDG_RUNTIME_DIR" to "run",
            "TMPDIR" to "tmp",
        )
        val ignored = listOf(
            notAbsolute("TMPDIR", "tmp"),
            notAbsolute("XDG_CONFIG_HOME", "config"),
            notAbsolute("XDG_DATA_HOME", "./data"),
            notAbsolute("XDG_STATE_HOME", "state"),
            notAbsolute("XDG_CACHE_HOME", "~/.cache"),
        )
        val expected = BaseDirs(
            home = Path("/home/me"),
            configHome = Path("/home/me/.config"),
            dataHome = Path("/home/me/.local/share"),
            stateHome = Path("/home/me/.local/state"),
            cacheHome = Path("/home/me/.cache"),
            tempHome = Path("/tmp"),
            ignored = ignored,
        )
        val linuxIgnored = ignored + notAbsolute("XDG_RUNTIME_DIR", "run")

        assertEquals(Ok(expected.copy(ignored = linuxIgnored)), baseDirFor("Linux", env, userHome = null))
        assertEquals(Ok(expected), baseDirFor("Mac OS X", env, userHome = "/Users/me", DirLayout.Xdg))
        assertEquals(Path("/tmp"), baseDirFor("Mac OS X", env, userHome = "/Users/me").assertSuccess().tempHome)
    }

    @Test
    fun `a relative HOME and XDG_CONFIG_HOME are listed as ignored with their values`() {
        val env = envOf(
            "HOME" to "rel",
            "XDG_CONFIG_HOME" to "cfg",
            "XDG_DATA_HOME" to "/data",
            "XDG_STATE_HOME" to " ",
        )

        val dirs = baseDirFor("Linux", env, userHome = "/home/account").assertSuccess()

        val blank = IgnoredVariable("XDG_STATE_HOME", " ", IgnoredBecause.Blank)
        assertEquals(listOf(notAbsolute("HOME", "rel"), notAbsolute("XDG_CONFIG_HOME", "cfg"), blank), dirs.ignored)
    }

    @Test
    fun `a relative HOME falls back to user home`() {
        val env = envOf("HOME" to "home/me")

        assertEquals(Path("/home/account"), baseDirFor("Linux", env, "/home/account").assertSuccess().home)
        assertEquals(
            Path("/Users/me/.config"),
            baseDirFor("Mac OS X", env, "/Users/me", DirLayout.Xdg).assertSuccess().configHome,
        )
        val unresolved = DirsError.UnableToResolve(DirKind.Home, ignored = listOf(notAbsolute("HOME", "home/me")))
        assertEquals(Err(unresolved), baseDirFor("Linux", env, userHome = null))
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

        assertEquals(Ok(expected), linuxWith(envOf("HOME" to "/home/me")) { "/home/account" })
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

        assertEquals(Ok(expected), linuxWith(env) { "/home/account" })
    }

    @Test
    fun `linux takes the runtime root from XDG_RUNTIME_DIR and has none without it`() {
        val env = envOf("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "/run/user/1000")

        assertEquals(Path("/run/user/1000"), linuxWith(env) { null }.assertSuccess().runtimeHome)
        assertNull(linuxWith(envOf("HOME" to "/home/me")) { null }.assertSuccess().runtimeHome)
    }

    @Test
    fun `linux takes XDG_RUNTIME_DIR only for an existing folder of this user's with no group or other bits`() {
        val scratch = Files.createTempDirectory("kern-dirs")
        try {
            val private = Files.createDirectory(scratch.resolve("private"), mode("rwx------"))
            val open = Files.createDirectory(scratch.resolve("open"))
            // Set after creating, since the umask would narrow a mode given to createDirectory.
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"))
            val link = Files.createSymbolicLink(scratch.resolve("link"), private)
            val file = Files.createFile(scratch.resolve("file"), mode("rwx------"))
            val missing = scratch.resolve("missing")
            val home = "HOME" to "/home/me"

            for (folder in listOf(private, link)) {
                val taken = baseDirFor("Linux", envOf(home, "XDG_RUNTIME_DIR" to "$folder"), userHome = null)
                assertEquals(Path("$folder"), taken.assertSuccess().runtimeHome)
            }
            for (folder in listOf(open, file, missing)) {
                val dirs = baseDirFor("Linux", envOf(home, "XDG_RUNTIME_DIR" to "$folder"), null).assertSuccess()

                assertNull(dirs.runtimeHome)
                val ignored = IgnoredVariable("XDG_RUNTIME_DIR", "$folder", IgnoredBecause.NotPrivateFolder)
                assertEquals(listOf(ignored), dirs.ignored)
            }
        } finally {
            Files.walk(scratch).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `fromXdg with every variable set takes each root and the private runtime folder`() {
        val scratch = Files.createTempDirectory("kern-dirs")
        try {
            val private = Files.createDirectory(scratch.resolve("private"), mode("rwx------"))
            // Set after creating, since the umask may narrow a mode given to createDirectory.
            Files.setPosixFilePermissions(private, PosixFilePermissions.fromString("rwx------"))
            val env = mapOf(
                "HOME" to "/home/me",
                "TMPDIR" to "/scratch/tmp",
                "XDG_CONFIG_HOME" to "/x/config",
                "XDG_DATA_HOME" to "/x/data",
                "XDG_STATE_HOME" to "/x/state",
                "XDG_CACHE_HOME" to "/x/cache",
                "XDG_RUNTIME_DIR" to "$private",
            )

            val expected = BaseDirs(
                home = Path("/home/me"),
                configHome = Path("/x/config"),
                dataHome = Path("/x/data"),
                stateHome = Path("/x/state"),
                cacheHome = Path("/x/cache"),
                tempHome = Path("/scratch/tmp"),
                runtimeHome = Path("$private"),
            )
            assertEquals(Ok(expected), BaseDirs.fromXdg(env, accountHome = Path("/home/account"), strict = true))
        } finally {
            Files.walk(scratch).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `the runtime folder's owner is matched by user id rather than by user name`() {
        val scratch = Files.createTempDirectory("kern-dirs")
        val userName = System.getProperty("user.name")
        try {
            val private = Files.createDirectory(scratch.resolve("private"), mode("rwx------"))
            System.setProperty("user.name", "someone-else")

            assertTrue(isPrivateFolder(Path("$private")))
        } finally {
            System.setProperty("user.name", userName)
            Files.walk(scratch).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `strict turns an XDG_RUNTIME_DIR that names no private folder into NotPrivate`() {
        val env = envOf("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "/nowhere/run")

        val refused = Err(DirsError.NotPrivate("XDG_RUNTIME_DIR", "/nowhere/run"))
        assertEquals(refused, baseDirFor("Linux", env, userHome = null, strict = true))
    }

    @Test
    fun `strict names the first variable read when a relative one comes before a runtime folder that is not private`() {
        val env = envOf("HOME" to "/home/me", "XDG_CACHE_HOME" to "cache", "XDG_RUNTIME_DIR" to "/nowhere/run")

        val refused = Err(DirsError.NotAbsolute("XDG_CACHE_HOME", "cache"))
        assertEquals(refused, baseDirFor("Linux", env, userHome = null, strict = true))
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
        assertEquals(Path("/home/account"), linuxWith(envOf()) { "/home/account" }.assertSuccess().home)
    }

    @Test
    fun `linux without any home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), linuxWith(envOf()) { null })
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
    fun `windows counts a relative APPDATA, LOCALAPPDATA or TEMP as unset`() {
        val all = mapOf("APPDATA" to "/roaming", "LOCALAPPDATA" to "/local", "TEMP" to "/temp")

        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Config, listOf(notAbsolute("APPDATA", "roaming")))),
            baseDirFor("Windows 11", (all + ("APPDATA" to "roaming"))::get, userHome = "/profile"),
        )
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Cache, listOf(notAbsolute("LOCALAPPDATA", "local")))),
            baseDirFor("Windows 11", (all + ("LOCALAPPDATA" to "local"))::get, userHome = "/profile"),
        )
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Temp, listOf(notAbsolute("TEMP", "temp")))),
            baseDirFor("Windows 11", (all + ("TEMP" to "temp"))::get, userHome = "/profile"),
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

    @Test
    fun `trailing separators on HOME are dropped`() {
        for (home in listOf("/x/home/", "/x/home//")) {
            val roots = baseDirFor("Linux", envOf("HOME" to home), userHome = null).assertSuccess()

            assertEquals(Path("/x/home"), roots.home)
            assertEquals(Path("/x/home/.config"), roots.configHome)
            assertEquals(Path("/x/home/.local/share"), roots.dataHome)
        }
    }

    @Test
    fun `a HOME of slash keeps the roots directly under it`() {
        val roots = baseDirFor("Linux", envOf("HOME" to "/"), userHome = null).assertSuccess()

        assertEquals(Path("/"), roots.home)
        assertEquals(Path("/.config"), roots.configHome)
    }

    @Test
    fun `trailing separators on an XDG variable are dropped`() {
        for (config in listOf("/x/config/", "/x/config//")) {
            val env = envOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to config)

            assertEquals(Path("/x/config"), baseDirFor("Linux", env, userHome = null).assertSuccess().configHome)
        }
    }

    @Test
    fun `strict turns the first variable read as not absolute into NotAbsolute`() {
        val env = envOf("HOME" to "rel", "XDG_CONFIG_HOME" to "cfg")

        assertEquals(Err(DirsError.NotAbsolute("HOME", "rel")), baseDirFor("Linux", env, "/home/me", strict = true))
        assertEquals(
            Err(DirsError.NotAbsolute("APPDATA", "roaming")),
            baseDirFor("Windows 11", envOf("APPDATA" to "roaming"), userHome = "/profile", strict = true),
        )
    }

    @Test
    fun `strict leaves roots read from absolute or unset variables as they are`() {
        val env = envOf("HOME" to "/home/me", "XDG_STATE_HOME" to "/state")

        assertEquals(baseDirFor("Linux", env, "/home/me"), baseDirFor("Linux", env, "/home/me", strict = true))
    }

    @Test
    fun `strict refuses a blank variable as Blank`() {
        val env = envOf("HOME" to "/home/me", "XDG_STATE_HOME" to " ")

        assertEquals(Err(DirsError.Blank("XDG_STATE_HOME", " ")), baseDirFor("Linux", env, "/home/me", strict = true))
    }

    @Test
    fun `strict names the variable rather than the root it left missing`() {
        assertEquals(
            Err(DirsError.NotAbsolute("HOME", "home/me")),
            baseDirFor("Linux", envOf("HOME" to "home/me"), userHome = null, strict = true),
        )
    }

    @Test
    fun `linux reads HOME then TMPDIR then the XDG variables in a fixed order`() {
        val read = mutableListOf<String>()

        linuxWith({ name -> "/${name.lowercase()}".also { read += name } }) { null }.assertSuccess()

        val expected = listOf(
            "HOME",
            "TMPDIR",
            "XDG_CONFIG_HOME",
            "XDG_DATA_HOME",
            "XDG_STATE_HOME",
            "XDG_CACHE_HOME",
            "XDG_RUNTIME_DIR",
        )
        assertEquals(expected, read)
    }

    @Test
    fun `strict with no home at all is UnableToResolve Home before a relative XDG variable is read`() {
        assertEquals(
            Err(DirsError.UnableToResolve(DirKind.Home)),
            baseDirFor("Linux", envOf("XDG_CACHE_HOME" to "cache"), userHome = null, strict = true),
        )
    }

    @Test
    fun `strict with the Xdg layout on macos turns a relative XDG variable into NotAbsolute`() {
        assertEquals(
            Err(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg")),
            baseDirFor("Mac OS X", envOf("XDG_CONFIG_HOME" to "cfg"), "/Users/me", DirLayout.Xdg, strict = true),
        )
    }
}
