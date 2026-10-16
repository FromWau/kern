package com.fromwau.kern.logger

import kotlin.test.Test
import kotlin.test.assertEquals

class ConstructorsJvmTest {
    // A second constructor, even an internal one, is listed to a user whose call matches no constructor.
    @Test
    fun `Logger has no constructor but the public one taking no arguments`() {
        val constructors = Logger::class.java.declaredConstructors.filterNot { it.isSynthetic }

        assertEquals(listOf(emptyList()), constructors.map { it.parameterTypes.toList() })
    }
}
