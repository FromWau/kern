package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
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
    fun `a blank name is InvalidAppName`() {
        assertEquals(Err(DirsError.InvalidAppName("")), base.forApp(""))
        assertEquals(Err(DirsError.InvalidAppName("  ")), base.forApp("  "))
    }
}
