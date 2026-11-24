package com.fromwau.kern.fs.coroutines

import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.util.Optional
import kotlin.io.path.Path as JavaPath
import kotlin.io.path.listDirectoryEntries
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

/**
 * Closes the lock's descriptor through the C library's `close`, which the JDK has no call for, by its foreign function
 * API, reached by reflection since this module compiles against an older JDK. Linux only, for `/proc/self/fd`.
 */
class AwaitLockReleaseJvmTest : AwaitLockReleaseTest() {
    @BeforeTest
    fun needsTheForeignFunctionApi() {
        assumeTrue(Runtime.version().feature() >= 22, "no foreign function API to close the descriptor before JDK 22")
    }

    override fun closeDescriptorOn(file: Path) {
        val open = JavaPath("/proc/self/fd").listDirectoryEntries().filter { entry ->
            runCatching { Files.readSymbolicLink(entry).toString() }.getOrNull() == file.toString()
        }
        assertEquals(0, cClose().invokeWithArguments(open.single().fileName.toString().toInt()))
    }

    private fun cClose(): MethodHandle {
        val linkerType = Class.forName("java.lang.foreign.Linker")
        val layoutType = Class.forName("java.lang.foreign.MemoryLayout")
        val descriptorType = Class.forName("java.lang.foreign.FunctionDescriptor")
        val segmentType = Class.forName("java.lang.foreign.MemorySegment")
        val optionType = Class.forName("java.lang.foreign.Linker\$Option")
        val int = Class.forName("java.lang.foreign.ValueLayout").getField("JAVA_INT").get(null)
        val linker = linkerType.getMethod("nativeLinker").invoke(null)
        val lookup = linkerType.getMethod("defaultLookup").invoke(linker)
        val find = Class.forName("java.lang.foreign.SymbolLookup").getMethod("find", String::class.java)
        val address = (find.invoke(lookup, "close") as Optional<*>).orElseThrow()
        val layouts = java.lang.reflect.Array.newInstance(layoutType, 1)
        java.lang.reflect.Array.set(layouts, 0, int)
        val descriptor = descriptorType.getMethod("of", layoutType, layouts.javaClass).invoke(null, int, layouts)
        val options = java.lang.reflect.Array.newInstance(optionType, 0)
        val downcall = linkerType.getMethod("downcallHandle", segmentType, descriptorType, options.javaClass)
        return downcall.invoke(linker, address, descriptor, options) as MethodHandle
    }
}
