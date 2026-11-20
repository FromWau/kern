package com.fromwau.kern.exec

import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAccessTest {
    @Test
    fun `a JDK that denies native access starts nothing and says how to allow it`() {
        // --illegal-native-access is there from Java 24 on.
        if (Runtime.version().feature() < 24) return
        val java = ProcessHandle.current().info().command().get()
        val events = Command(
            listOf(
                java,
                "--illegal-native-access=deny",
                "-cp",
                System.getProperty("java.class.path"),
                StartEcho::class.java.name,
            ),
        ).events()

        val expected = "${ExecError.Unsupported(NATIVE_ACCESS_DENIED)}\n"
        assertEquals(expected, events.text(ChildStream.Stdout), events.text(ChildStream.Stderr))
    }
}

/** Starts `echo hi` in a JVM of its own and prints the error its start answers. */
object StartEcho {
    @JvmStatic
    fun main(args: Array<String>) {
        println(SystemStarter.start(Command(listOf("/bin/echo", "hi"))).assertError())
    }
}
