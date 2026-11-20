package com.fromwau.kern.exec

import kotlin.test.Test
import kotlin.test.assertEquals

class RawEnvironmentTest {
    @Test
    fun `an inherited variable that is not UTF-8 reaches the child byte for byte`() {
        // A JVM cannot set a variable of its own, so a JVM started with one runs the check.
        val events = sh(
            "KERN_RAW=\"\$(printf 'a\\377b')\" exec \"\$1\" --enable-native-access=ALL-UNNAMED -cp \"\$2\" \"\$3\"",
            ProcessHandle.current().info().command().get(),
            System.getProperty("java.class.path"),
            CheckRawVariable::class.java.name,
        ).events()

        assertEquals("[same, same]\n", events.text(ChildStream.Stdout), events.text(ChildStream.Stderr))
    }
}

/** Prints what [rawVariableCheck] answers for each of [inheritingEnvironments]. */
object CheckRawVariable {
    @JvmStatic
    fun main(args: Array<String>) {
        println(inheritingEnvironments.map(::rawVariableCheck))
    }
}
