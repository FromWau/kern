package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

class BaseDirsTest {
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
        val runId = Uuid.parse("00000000-0000-0000-0000-000000000001")
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
    fun `forApp draws a new run id on every call`() {
        assertNotEquals(base.forApp("myapp").getOrNull()?.temp, base.forApp("myapp").getOrNull()?.temp)
    }

    @Test
    fun `a blank name is InvalidAppName`() {
        assertEquals(Err(DirsError.InvalidAppName("")), base.forApp(""))
        assertEquals(Err(DirsError.InvalidAppName("  ")), base.forApp("  "))
    }
}
