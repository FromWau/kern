package com.fromwau.kern.result

import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.test.Test

/** Serialization is a compileOnly dependency on the JVM, so every error type must load without it. */
class WithoutSerializationTest {
    private val loader = URLClassLoader(
        arrayOf(
            AllErrors::class.java.protectionDomain.codeSource.location,
            Unit::class.java.protectionDomain.codeSource.location,
        ),
        ClassLoader.getPlatformClassLoader(),
    )

    private val error: Any = loader.loadClass(ResultError::class.java.name).let { type ->
        Proxy.newProxyInstance(loader, arrayOf(type)) { _, _, _ -> null }
    }

    private fun build(type: Class<*>, vararg args: Any): Any =
        loader.loadClass(type.name).constructors.single { it.parameterCount == args.size }.newInstance(*args)

    @Test
    fun `an error result builds without kotlinx-serialization on the classpath`() {
        val _ = build(Result.Error::class.java, error)
    }

    @Test
    fun `AllErrors builds without kotlinx-serialization on the classpath`() {
        val _ = build(AllErrors::class.java, listOf(error))
    }

    @Test
    fun `Partial builds without kotlinx-serialization on the classpath`() {
        val _ = build(Partial::class.java, emptyList<Any>(), error)
    }
}
