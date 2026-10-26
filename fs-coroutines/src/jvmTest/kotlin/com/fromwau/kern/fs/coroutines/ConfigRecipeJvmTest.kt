package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.delete
import com.fromwau.kern.fs.deleteRecursively
import com.fromwau.kern.fs.div
import com.fromwau.kern.fs.realPath
import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.fs.test.scratchDir
import com.fromwau.kern.fs.test.symlinkTo
import com.fromwau.kern.fs.writeText
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The recipe waits for the real operating system's events, so these run on Dispatchers.Default in real time.
class ConfigRecipeJvmTest {
    private val dir = scratchDir()
    private val printed = Channel<String>(Channel.UNLIMITED)
    private val loads = Channel<String>(Channel.UNLIMITED)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively().assertSuccess()
    }

    /** Stands in for the `println` the recipe calls, so a test can read what it prints. */
    private fun println(message: String) {
        val _ = printed.trySend(message)
    }

    private fun loaded(text: String) {
        val _ = loads.trySend(text)
    }

    /** The config watch from fs's docs/reading.md, as written there. */
    suspend fun watchConfig(config: Path, load: (String) -> Unit) {
        config.textChanges(maxBytes = 1_048_576, trusted = true).collect { change ->
            change.onSuccess(load).onError { failure ->
                when (failure) {
                    is FileError.NotFound -> println("$config is not there, so nothing new is loaded")
                    is FileError.Refused -> println("${failure.path} is not yours alone")
                    else -> println("$config not read: $failure")
                }
            }
        }
    }

    private fun watching(
        config: Path,
        block: suspend () -> Unit,
    ) = runTest {
        withContext(Dispatchers.Default) {
            coroutineScope {
                val watch = launch { watchConfig(config, ::loaded) }
                try {
                    block()
                } finally {
                    watch.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun ReceiveChannel<String>.next(): String = withTimeout(10.seconds) { receive() }

    /** Whether nothing more came for a while. */
    private suspend fun ReceiveChannel<String>.quiet(): Boolean =
        withTimeoutOrNull(300.milliseconds) { receive() } == null

    private fun privateFolder(): Path = (dir / "config").also {
        it.createDirectories().assertSuccess()
        it.chmod(0b111_000_000).assertSuccess()
    }

    private fun Path.privateConfig(text: String): Path = also { it.writeText(text, ownerOnly = true).assertSuccess() }

    @Test
    fun `the config watch loads the config and again after a write that kept the old modification time`() {
        val config = (privateFolder() / "app.toml").privateConfig("a = 1")

        watching(config) {
            assertEquals("a = 1", loads.next())
            val modified = Files.getLastModifiedTime(Paths.get(config.toString()))
            // The kernel stamps changes from a coarse clock, so a rewrite at once could keep the same change time.
            Thread.sleep(50)
            config.writeText("a = 2").assertSuccess()
            Files.setLastModifiedTime(Paths.get(config.toString()), modified)
            assertEquals("a = 2", loads.next())
            assertTrue(printed.quiet())
        }
    }

    @Test
    fun `the config watch loads nothing from a folder the group may write`() {
        val folder = privateFolder()
        val config = (folder / "app.toml").privateConfig("a = 1")
        folder.chmod(0b111_111_000).assertSuccess()

        watching(config) {
            assertEquals("$folder is not yours alone", printed.next())
            assertTrue(loads.quiet())
        }
    }

    @Test
    fun `the config watch says again when another check refuses and loads the config once it is safe`() {
        val folder = privateFolder()
        val config = (folder / "app.toml").privateConfig("a = 1")
        config.chmod(0b110_110_000).assertSuccess()

        watching(config) {
            assertEquals("$config is not yours alone", printed.next())
            folder.chmod(0b111_111_000).assertSuccess()
            config.chmod(0b110_000_000).assertSuccess()
            assertEquals("$folder is not yours alone", printed.next())
            folder.chmod(0b111_000_000).assertSuccess()
            config.chmod(0b110_100_000).assertSuccess()
            assertEquals("a = 1", loads.next())
            config.chmod(0b110_110_000).assertSuccess()
            assertEquals("$config is not yours alone", printed.next())
            assertTrue(printed.quiet())
        }
    }

    @Test
    fun `the config watch loads nothing new after a chmod that leaves the text as it was`() {
        val config = (privateFolder() / "app.toml").privateConfig("a = 1")

        watching(config) {
            assertEquals("a = 1", loads.next())
            config.chmod(0b100_000_000).assertSuccess()
            assertTrue(loads.quiet())
            assertTrue(printed.quiet())
        }
    }

    @Test
    fun `the config watch loads a config that is a link into a dotfiles folder of your own`() {
        val mine = privateFolder()
        val dotfiles = (dir / "dotfiles").also { it.createDirectories().assertSuccess() }
        dotfiles.chmod(0b111_000_000).assertSuccess()
        val real = (dotfiles / "app.toml").privateConfig("a = 1")
        val config = mine / "app.toml"
        config.symlinkTo(real).assertSuccess()

        watching(config) {
            assertEquals("a = 1", loads.next())
            assertTrue(printed.quiet())
        }
    }

    @Test
    fun `the config watch loads nothing through a link to a file in a folder the group may write`() {
        val mine = privateFolder()
        val open = (dir / "open").also { it.createDirectories().assertSuccess() }
        open.chmod(0b111_111_000).assertSuccess()
        val real = (open / "app.toml").privateConfig("a = 1")
        val config = mine / "app.toml"
        config.symlinkTo(real).assertSuccess()

        watching(config) {
            assertEquals("${open.realPath().assertSuccess()} is not yours alone", printed.next())
            assertTrue(loads.quiet())
        }
    }

    @Test
    fun `the config watch loads nothing through a link that sits in a folder the group may write`() {
        val mine = privateFolder()
        val open = (dir / "open").also { it.createDirectories().assertSuccess() }
        open.chmod(0b111_111_000).assertSuccess()
        val real = (mine / "app.toml").privateConfig("a = 1")
        val config = open / "app.toml"
        config.symlinkTo(real).assertSuccess()

        watching(config) {
            assertEquals("$open is not yours alone", printed.next())
            assertTrue(loads.quiet())
        }
    }

    @Test
    fun `the config watch says once that a missing config folder loads nothing and loads the config once it is there`() {
        val folder = dir / "config"
        val config = folder / "app.toml"

        watching(config) {
            assertEquals("$config is not there, so nothing new is loaded", printed.next())
            folder.createDirectories().assertSuccess()
            folder.chmod(0b111_000_000).assertSuccess()
            assertTrue(printed.quiet())
            val _ = config.privateConfig("a = 1")
            assertEquals("a = 1", loads.next())
            assertTrue(printed.quiet())
        }
    }

    @Test
    fun `the config watch keeps the config last loaded once it is deleted and says once that nothing new is loaded`() {
        val config = (privateFolder() / "app.toml").privateConfig("a = 1")

        watching(config) {
            assertEquals("a = 1", loads.next())
            config.delete().assertSuccess()
            assertEquals("$config is not there, so nothing new is loaded", printed.next())
            assertTrue(printed.quiet())
            assertTrue(loads.quiet())
        }
    }

    @Test
    fun `the config watch warns again about a config that comes back unsafe after it was deleted`() {
        val folder = privateFolder()
        val config = (folder / "app.toml").privateConfig("a = 1")
        config.chmod(0b110_110_000).assertSuccess()
        val unsafeCopy = (folder / "unsafe.toml").privateConfig("a = 2")
        unsafeCopy.chmod(0b110_110_000).assertSuccess()

        watching(config) {
            assertEquals("$config is not yours alone", printed.next())
            config.delete().assertSuccess()
            assertEquals("$config is not there, so nothing new is loaded", printed.next())
            // A rename puts the unsafe config back in one step, so no change ever sees it safe.
            Files.move(Paths.get(unsafeCopy.toString()), Paths.get(config.toString()))
            assertEquals("$config is not yours alone", printed.next())
            assertTrue(loads.quiet())
        }
    }
}
