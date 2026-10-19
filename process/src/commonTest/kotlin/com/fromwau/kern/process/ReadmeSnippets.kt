package com.fromwau.kern.process

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.onError

// The README's samples, compiled here so they keep compiling without warnings. Nothing calls them.

private class Server {
    fun stop() = Unit

    fun awaitTermination() = Unit
}

private fun startServer() = Server()

private fun stoppingAServer() {
    val server = startServer()
    onShutdownRequest { server.stop() }
        .onError { println("shutdown requests are not handled: $it") }
    server.awaitTermination()
}

private fun handlingEveryError(server: Server) {
    when (val installed = onShutdownRequest { server.stop() }) {
        is Result.Success -> Unit
        is Result.Error -> when (val error = installed.error) {
            ShutdownError.AlreadyInstalled -> println("another part of the program already handles shutdown requests")
            ShutdownError.Unsupported -> println("this platform sends no shutdown requests")
            is ShutdownError.Reserved -> println("the JVM keeps ${error.requests} for itself")
            ShutdownError.OutOfResources -> println("out of resources")
            is ShutdownError.System -> println("the system refused: ${error.code}")
        }
    }
}

private class WithExitCode(private val source: ShutdownSource, private val code: Int) : ShutdownSource {
    override fun onShutdownRequest(
        exitCode: Int?,
        handler: (ShutdownRequest) -> Unit,
    ): Result<Unit, ShutdownError> = source.onShutdownRequest(exitCode ?: code, handler)

    override fun stopOnShutdownRequest(exitCode: Int?): Result<ShutdownStop, ShutdownError> =
        source.stopOnShutdownRequest(exitCode ?: code)
}

private class LibraryServer(source: ShutdownSource)

private fun wrappingSystemShutdownSource(): LibraryServer {
    val server = LibraryServer(WithExitCode(systemShutdownSource(), code = 0))
    return server
}

private fun processNextJob() = Unit

private fun deleteTempFiles() = Unit

private fun stoppingAWorkerLoop() {
    val stop = stopOnShutdownRequest()
        .onError { println("shutdown requests are not handled: $it") }
        .getOrNull()
    try {
        while (stop?.requested != true) processNextJob()
    } finally {
        deleteTempFiles()
    }
}
