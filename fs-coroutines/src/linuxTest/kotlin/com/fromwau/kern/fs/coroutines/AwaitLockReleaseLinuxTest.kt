@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs.coroutines

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import platform.posix.PATH_MAX
import platform.posix.close
import platform.posix.readlink

class AwaitLockReleaseLinuxTest : AwaitLockReleaseTest() {
    override fun closeDescriptorOn(file: Path) {
        val open = (0 until 1024).filter { descriptor ->
            memScoped {
                val target = allocArray<ByteVar>(PATH_MAX)
                val length = readlink("/proc/self/fd/$descriptor", target, PATH_MAX.convert())
                length > 0 && target.readBytes(length.toInt()).decodeToString() == file.toString()
            }
        }
        assertEquals(0, close(open.single()))
    }
}
