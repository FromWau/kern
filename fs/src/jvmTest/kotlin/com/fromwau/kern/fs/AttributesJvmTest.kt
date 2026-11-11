package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.UserDefinedFileAttributeView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

private const val ATTRIBUTE = "kern"

class AttributesJvmTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = dir.deleteTree()

    /** Runs [block] on a file carrying an attribute, on Linux, the one system whose JVM looks, where it takes one. */
    private fun withAttribute(block: (Path) -> Unit) {
        assumeTrue(System.getProperty("os.name") == "Linux", "the JVM looks for attributes on Linux only")
        val file = (dir / "a.toml").writeRaw("old")
        assumeTrue(file.setAttribute(), "$dir takes no user attribute")
        block(file)
    }

    @Test
    fun `an Auto write of a file with a user attribute renames and keeps it`() = withAttribute { file ->
        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())

        assertEquals("new", file.readRaw())
        assertEquals(listOf(ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `an Atomic write of a file with a user attribute renames and keeps it`() = withAttribute { file ->
        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Atomic).way())

        assertEquals(listOf(ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `a Replace write of a file with a user attribute renames and keeps it`() = withAttribute { file ->
        assertEquals(Ok(WriteWay.Renamed), file.writeText("new", WriteMode.Replace).way())

        assertEquals(listOf(ATTRIBUTE), file.attributeNames())
    }

    @Test
    fun `an Auto write of a file with no attributes renames`() {
        val file = (dir / "a.toml").writeRaw("old")

        assertEquals(Ok(WriteWay.Renamed), file.writeText("new").way())
    }
}

private fun Path.view(): UserDefinedFileAttributeView? =
    Files.getFileAttributeView(Paths.get(toString()), UserDefinedFileAttributeView::class.java)

private fun Path.setAttribute(): Boolean = try {
    view()?.write(ATTRIBUTE, ByteBuffer.wrap(byteArrayOf(1))) != null
} catch (_: IOException) {
    false
}

private fun Path.attributeNames(): List<String> = checkNotNull(view()).list()
