package com.fromwau.kern.dirs

import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ObsoleteWorkersApi::class)
class CreateDirectoriesRaceLinuxTest {
    private val dir = newTempDir()
    private val workers = List(2) { Worker.start() }

    @AfterTest
    fun cleanUp() {
        workers.forEach { it.requestTermination().result }
        dir.deleteTree()
    }

    @Test
    fun `two callers creating the same directories at once both succeed`() {
        repeat(20) { round ->
            val deepest = (1..20).fold(dir / "round$round") { path, level -> path / "level$level" }
            val created = workers
                .map { worker ->
                    worker.execute(TransferMode.SAFE, { deepest.toString() }) { raw ->
                        Path(raw).createDirectories() is Result.Success
                    }
                }
                .map { it.result }

            assertEquals(listOf(true, true), created, "round $round")
        }
    }
}
