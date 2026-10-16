package com.fromwau.kern.logger.file

import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.SinkFailure
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructorsJvmTest {
    // A second constructor, even an internal one, is listed to a user whose call matches no constructor.
    @Test
    fun `FileSink has no constructor but the public one`() {
        val constructors = FileSink::class.java.declaredConstructors.filterNot { it.isSynthetic }

        val public = listOf(
            Path::class.java,
            LogFormat::class.java,
            String::class.java,
            SinkFailure::class.java,
            Int::class.java,
            DropPolicy::class.java,
        )
        assertEquals(listOf(public), constructors.map { it.parameterTypes.toList() })
    }
}
