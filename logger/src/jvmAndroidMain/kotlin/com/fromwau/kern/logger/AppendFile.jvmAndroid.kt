package com.fromwau.kern.logger

import kotlinx.io.files.Path
import java.io.FileOutputStream

// FileOutputStream.write(ByteArray) hands the whole array to one system write, opened with O_APPEND.
internal actual fun openAppendFile(path: Path): AppendFile {
    val out = FileOutputStream(path.toString(), true)
    return object : AppendFile {
        override fun append(bytes: ByteArray) = out.write(bytes)

        override fun close() = out.close()
    }
}
