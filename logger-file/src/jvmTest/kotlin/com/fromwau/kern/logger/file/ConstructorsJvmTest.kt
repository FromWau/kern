package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.Writer
import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.SinkFailure
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructorsJvmTest {
    // Every constructor, even an internal one, is listed to a user whose call matches none.
    @Test
    fun `FileSink has no constructors but the public ones`() {
        val constructors = FileSink::class.java.declaredConstructors.filterNot { it.isSynthetic }

        val byPath = listOf(
            Path::class.java,
            LogFormat::class.java,
            String::class.java,
            SinkFailure::class.java,
            Int::class.java,
            DropPolicy::class.java,
        )
        val byWriter = listOf(
            Writer::class.java,
            String::class.java,
            LogFormat::class.java,
            String::class.java,
            SinkFailure::class.java,
            Int::class.java,
            DropPolicy::class.java,
        )
        assertEquals(setOf(byPath, byWriter), constructors.map { it.parameterTypes.toList() }.toSet())
    }
}
