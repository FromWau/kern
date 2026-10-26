package com.fromwau.kern.dirs

import com.fromwau.kern.dirs.IgnoredBecause.NotAbsolute
import com.fromwau.kern.dirs.IgnoredBecause.NotPrivateFolder
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class BaseDirsTest {
    private val runId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val barApp = AppId(qualifier = "com", organization = "Foo Corp", application = "Bar App")
    private val base = BaseDirs(
        home = Path("/home/me"),
        configHome = Path("/config"),
        dataHome = Path("/data"),
        stateHome = Path("/state"),
        cacheHome = Path("/cache"),
        tempHome = Path("/tmp"),
    )

    @Test
    fun `an ignored relative variable as an error is NotAbsolute naming it and its value`() {
        val ignored = IgnoredVariable("XDG_CONFIG_HOME", "cfg", NotAbsolute)

        assertEquals(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg"), ignored.toError())
    }

    @Test
    fun `an ignored runtime folder that is not private as an error is NotPrivate naming it and its value`() {
        val ignored = IgnoredVariable("XDG_RUNTIME_DIR", "/run/open", NotPrivateFolder)

        assertEquals(DirsError.NotPrivate("XDG_RUNTIME_DIR", "/run/open"), ignored.toError())
    }

    @Test
    fun `forApp appends the name to each root and the run id to temp`() {
        val expected = AppDirs(
            config = Path("/config/myapp"),
            data = Path("/data/myapp"),
            state = Path("/state/myapp"),
            cache = Path("/cache/myapp"),
            temp = Path("/tmp/myapp/00000000-0000-0000-0000-000000000001"),
        )

        assertEquals(Ok(expected), base.forApp("myapp", runId))
    }

    @Test
    fun `cache sharing the state root goes in a folder of its own inside the app's state`() {
        val shared = base.copy(cacheHome = base.stateHome).forApp("myapp").assertSuccess()

        assertEquals(Path("/state/myapp"), shared.state)
        assertEquals(Path("/state/myapp/cache"), shared.cache)
    }

    @Test
    fun `runtime gets the name appended where the platform has a runtime root`() {
        val withRuntime = base.copy(runtimeHome = Path("/run/user/1000"))

        assertEquals(Path("/run/user/1000/myapp"), withRuntime.forApp("myapp").assertSuccess().runtime)
        assertNull(base.forApp("myapp").assertSuccess().runtime)
    }

    @Test
    fun `an app id under the XDG naming is the application lowercased with its words joined`() {
        val dirs = base.copy(naming = AppNaming.Xdg).forApp(barApp, runId).assertSuccess()

        assertEquals(Path("/config/barapp"), dirs.config)
        assertEquals(Path("/tmp/barapp/$runId"), dirs.temp)
    }

    @Test
    fun `an app id under the Apple naming is a bundle identifier`() {
        val dirs = base.copy(naming = AppNaming.Apple).forApp(barApp).assertSuccess()

        assertEquals(Path("/config/com.Foo-Corp.Bar-App"), dirs.config)
        assertEquals(
            Path("/config/Foo-Corp.Bar-App"),
            base.copy(naming = AppNaming.Apple).forApp(barApp.copy(qualifier = "")).assertSuccess().config,
        )
    }

    @Test
    fun `an app id under the Windows naming is the application's folder inside the organization's`() {
        val windows = base.copy(naming = AppNaming.Windows, cacheHome = base.stateHome)
        val dirs = windows.forApp(barApp).assertSuccess()

        assertEquals(Path("/config/Foo Corp/Bar App"), dirs.config)
        assertEquals(Path("/state/Foo Corp/Bar App/cache"), dirs.cache)
        assertEquals(Path("/config/Bar App"), windows.forApp(barApp.copy(organization = "")).assertSuccess().config)
    }

    @Test
    fun `an app id without an application name is InvalidAppName under every naming`() {
        val nameless = barApp.copy(application = "")

        for (naming in AppNaming.entries) {
            assertEquals(Err(DirsError.InvalidAppName("")), base.copy(naming = naming).forApp(nameless))
        }
    }

    @Test
    fun `an app id that does not make one folder name per level is InvalidAppName`() {
        assertEquals(Err(DirsError.InvalidAppName(" ")), base.forApp(barApp.copy(application = " ")))
        assertEquals(
            Err(DirsError.InvalidAppName("Foo/Corp")),
            base.copy(naming = AppNaming.Windows).forApp(barApp.copy(organization = "Foo/Corp")),
        )
    }

    @Test
    fun `forApp draws a new run id on every call`() {
        assertNotEquals(base.forApp("myapp").assertSuccess().temp, base.forApp("myapp").assertSuccess().temp)
    }

    @Test
    fun `a name that is not one folder name is InvalidAppName`() {
        for (name in listOf(".", "..", "my/app", "my\\app", "/abs")) {
            assertEquals(Err(DirsError.InvalidAppName(name)), base.forApp(name))
        }
        assertEquals(Path(base.configHome, ".myapp"), base.forApp(".myapp").assertSuccess().config)
    }

    @Test
    fun `a name holding a NUL is InvalidAppName`() {
        assertEquals(Err(DirsError.InvalidAppName("my\u0000app")), base.forApp("my\u0000app"))
        assertEquals(
            Err(DirsError.InvalidAppName("Foo\u0000Corp")),
            base.copy(naming = AppNaming.Windows).forApp(barApp.copy(organization = "Foo\u0000Corp")),
        )
    }

    @Test
    fun `a name longer than 255 bytes in UTF-8 is InvalidAppName`() {
        val longest = "ä".repeat(127) + "a"

        assertEquals(Path(base.configHome, longest), base.forApp(longest).assertSuccess().config)
        assertEquals(Err(DirsError.InvalidAppName(longest + "a")), base.forApp(longest + "a"))
    }

    @Test
    fun `a blank name is InvalidAppName`() {
        assertEquals(Err(DirsError.InvalidAppName("")), base.forApp(""))
        assertEquals(Err(DirsError.InvalidAppName("  ")), base.forApp("  "))
    }

    @Test
    fun `forApp hands the app the variables its roots ignored`() {
        val ignored = listOf(IgnoredVariable("XDG_CONFIG_HOME", "cfg", NotAbsolute))

        assertEquals(ignored, base.copy(ignored = ignored).forApp("myapp", runId).assertSuccess().ignored)
        assertEquals(ignored, base.copy(ignored = ignored).forApp(barApp, runId).assertSuccess().ignored)
    }

    @Test
    fun `a root that cannot be resolved carries the variables ignored on the way`() {
        val variables = CheckedVariables(mapOf("HOME" to "rel", "TMPDIR" to "/tmp")::get)
        val _ = variables("HOME")
        val _ = variables("TMPDIR")

        val unresolved = Err(DirsError.UnableToResolve(DirKind.Home)).withIgnored(variables, strict = false)

        val ignored = listOf(IgnoredVariable("HOME", "rel", NotAbsolute))
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home, ignored)), unresolved)
    }

    @Test
    fun `roots under a scratch folder give the app's folders under it`() {
        val scratch = Path("/scratch")

        // The README's In a test example, as written there.
        val dirs: AppDirs = BaseDirs.under(scratch).forApp("myapp").getOrElse { error("not one folder name: $it") }

        val fixed = AppDirs(
            config = Path(scratch, "config"),
            data = Path(scratch, "data"),
            state = Path(scratch, "state"),
            cache = Path(scratch, "cache"),
            temp = Path(scratch, "tmp", "run"),
        )

        assertEquals(Path("/scratch/state/myapp"), dirs.state)
        assertEquals(Path("/scratch/tmp/myapp"), dirs.temp.parent)
        assertEquals(Path("/scratch/run/myapp"), dirs.runtime)
        assertEquals(Path("/scratch/tmp/run"), fixed.temp)
    }

    @Test
    fun `under puts every root in a folder of its own below the one given`() {
        val expected = BaseDirs(
            home = Path("/scratch/home"),
            configHome = Path("/scratch/config"),
            dataHome = Path("/scratch/data"),
            stateHome = Path("/scratch/state"),
            cacheHome = Path("/scratch/cache"),
            tempHome = Path("/scratch/tmp"),
            runtimeHome = Path("/scratch/run"),
            naming = AppNaming.Xdg,
            ignored = emptyList(),
        )

        assertEquals(expected, BaseDirs.under(Path("/scratch")))
    }

    @Test
    fun `under takes the naming an app id is named by`() {
        assertEquals(AppNaming.Windows, BaseDirs.under(Path("/scratch"), AppNaming.Windows).naming)
    }

    @Test
    fun `forApp works on roots under one folder`() {
        val expected = AppDirs(
            config = Path("/scratch/config/myapp"),
            data = Path("/scratch/data/myapp"),
            state = Path("/scratch/state/myapp"),
            cache = Path("/scratch/cache/myapp"),
            temp = Path("/scratch/tmp/myapp/00000000-0000-0000-0000-000000000001"),
            runtime = Path("/scratch/run/myapp"),
        )

        assertEquals(Ok(expected), BaseDirs.under(Path("/scratch")).forApp("myapp", runId))
    }
}
