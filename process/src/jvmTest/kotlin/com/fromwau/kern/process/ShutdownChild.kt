package com.fromwau.kern.process

import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.isSuccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import sun.misc.Signal
import sun.misc.SignalHandler

/** The program [ShutdownJvmTest] signals: installs a handler as `args[0]` says, prints `ready`, then waits. */
object ShutdownChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            "handle" -> install { request -> println("handled $request on ${Thread.currentThread().name}") }
            // Long enough for a second request to end the process first, short enough to print when it does not.
            "block" -> install { request ->
                println("handling $request")
                CountDownLatch(1).await(5, TimeUnit.SECONDS)
                println("rest of the handler")
            }
            "throw" -> install { error("the handler failed") }
            "throw exit 0" -> install(exitCode = 0) { error("the handler failed") }
            "main exits as the handler returns" -> {
                val handlerReturning = CountDownLatch(1)
                install(exitCode = 0) { handlerReturning.countDown() }
                println("ready")
                handlerReturning.await()
                exitProcess(1)
            }
            "exit3" -> install { exitProcess(3) }
            "slow app hook" -> {
                addSlowAppHook()
                install { request -> println("handled $request") }
            }
            // The handler calls exitProcess once main's exit has started and kern's hook is waiting for the handler.
            "handler exits during main's exit" -> {
                val handlerStarted = CountDownLatch(1)
                install {
                    handlerStarted.countDown()
                    while (!kernHookIsWaiting()) Thread.onSpinWait()
                    exitProcess(7)
                }
                println("ready")
                handlerStarted.await()
                exitProcess(3)
            }
            // The handler throws an exception that cannot be printed once main's exit is waiting for the handler.
            "unprintable during main's exit" -> {
                val handlerStarted = CountDownLatch(1)
                val exitStarted = CountDownLatch(1)
                Runtime.getRuntime().addShutdownHook(Thread { exitStarted.countDown() })
                install {
                    handlerStarted.countDown()
                    exitStarted.await()
                    throw Unprintable()
                }
                println("ready")
                handlerStarted.await()
                exitProcess(3)
            }
            "handle exit 0" -> install(exitCode = 0) { request -> println("handled $request") }
            "block exit 0" -> install(exitCode = 0) { request ->
                println("handling $request")
                CountDownLatch(1).await()
            }
            // Main exits while the handler runs, and the handler finishes only once that exit has started.
            "main exits 3" -> {
                val handlerStarted = CountDownLatch(1)
                val exitStarted = CountDownLatch(1)
                Runtime.getRuntime().addShutdownHook(Thread { exitStarted.countDown() })
                install(exitCode = 5) {
                    handlerStarted.countDown()
                    exitStarted.await()
                    println("handler finished")
                }
                println("ready")
                handlerStarted.await()
                println("main exits 3")
                exitProcess(3)
            }
            // Main returns while the handler runs, and the handler finishes only once main has returned.
            "main returns" -> {
                val mainThread = Thread.currentThread()
                val handlerStarted = CountDownLatch(1)
                install {
                    handlerStarted.countDown()
                    mainThread.join()
                    println("handler finished")
                }
                println("ready")
                handlerStarted.await()
                println("main returned")
                return
            }
            "stop" -> {
                stopLoop(exitCode = null)
                return
            }
            "stop exit 0" -> {
                stopLoop(exitCode = 0)
                return
            }
            "stop then exit 7" -> {
                stopLoop(exitCode = null)
                exitProcess(7)
            }
            "stop, slow app hook" -> {
                addSlowAppHook()
                stopLoop(exitCode = null)
                return
            }
            "stop, slow app hook, exit 7" -> {
                addSlowAppHook()
                stopLoop(exitCode = null)
                exitProcess(7)
            }
            "stop, endless worker, main returned" -> {
                mainReturnsBeforeTheRequest(exitCode = null)
                return
            }
            "stop exit 0, endless worker, main returned" -> {
                mainReturnsBeforeTheRequest(exitCode = 0)
                return
            }
            "stop, endless worker, main returns after the request" -> {
                startEndlessWorker()
                awaitStopRequest(exitCode = null)
                println("main returns")
                return
            }
            "stop, endless worker after the request, main returns" -> {
                workerAfterTheRequest(exitCode = null)
                return
            }
            "stop exit 0, endless worker after the request, main returns" -> {
                workerAfterTheRequest(exitCode = 0)
                return
            }
            "stop, slow daemon app hook, endless worker after the request, main returns" -> {
                val hook = Thread {
                    Thread.sleep(500)
                    println("daemon hook finished")
                }
                hook.isDaemon = true
                Runtime.getRuntime().addShutdownHook(hook)
                workerAfterTheRequest(exitCode = null)
                return
            }
            "stop, slow app hook, endless worker after the request, main returns" -> {
                addSlowAppHook()
                workerAfterTheRequest(exitCode = null)
                return
            }
            // A thread started after the request exits while the exit main's return started is already under way.
            "stop, late exit 9, main returns" -> {
                stopThenLateExit()
                return
            }
            "stop, late exit 9, main runs" -> {
                stopThenLateExit()
                Thread.sleep(10_000)
                return
            }
            // A worker that never ends, started before the request: an exit must not wait for it.
            "stop, endless worker, exit 7" -> {
                startEndlessWorker()
                stopLoop(exitCode = null)
                exitProcess(7)
            }
            // Long enough for a second request to end the process first, short enough to print when it does not.
            "stop, slow job" -> {
                awaitStopRequest(exitCode = null)
                println("stop requested")
                Thread.sleep(5_000)
                println("rest of the job")
                return
            }
            "stop then handler" -> {
                println(stopOnShutdownRequest().errorOrNull())
                println(onShutdownRequest {}.errorOrNull())
                return
            }
            "handler then stop" -> {
                println(onShutdownRequest {}.errorOrNull())
                println(systemShutdownSource().stopOnShutdownRequest().errorOrNull())
                return
            }
            "twice" -> {
                println(onShutdownRequest {}.errorOrNull())
                println(systemShutdownSource().onShutdownRequest {}.errorOrNull())
                return
            }
            "reserved" -> {
                println(onShutdownRequest {}.errorOrNull())
                return
            }
            "reserved, hook" -> {
                println(onShutdownRequest {}.errorOrNull())
                val hook = lastShutdownHook
                println(if (hook != null && Runtime.getRuntime().removeShutdownHook(hook)) "hook left" else "no hook")
                return
            }
            "install during exit" -> {
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        val failure = runCatching { onShutdownRequest {} }.exceptionOrNull()
                        println(failure?.javaClass?.simpleName)
                        // Swapping the handler out shows which one is installed: the JVM's, or one of kern's lambdas.
                        val installed = Signal.handle(Signal("TERM"), SignalHandler.SIG_DFL).javaClass.name
                        println(if (installed.startsWith("com.fromwau.kern.")) "TERM replaced" else "TERM untouched")
                    },
                )
                exitProcess(0)
            }
        }
        println("ready")
        CountDownLatch(1).await()
    }

    private class Unprintable : Exception() {
        override fun toString(): String = throw IllegalStateException("cannot print")
    }

    private fun installStop(exitCode: Int?): ShutdownStop {
        val installed = stopOnShutdownRequest(exitCode)
        return checkNotNull(installed.getOrNull()) { "not installed: $installed" }
    }

    /** Installs a stop, prints `ready`, and polls it until a request arrives. */
    @IgnorableReturnValue
    private fun awaitStopRequest(exitCode: Int?): ShutdownStop {
        val stop = installStop(exitCode)
        println("ready")
        while (!stop.requested) Thread.sleep(10)
        return stop
    }

    /** Polls the stop until a request arrives, then finishes its job and returns from main. */
    private fun stopLoop(exitCode: Int?) {
        val stop = awaitStopRequest(exitCode)
        println("job finished after ${stop.request}")
    }

    /** Starts a worker that never ends once the request arrived, then returns from main without joining it. */
    private fun workerAfterTheRequest(exitCode: Int?) {
        awaitStopRequest(exitCode)
        startEndlessWorker()
        println("main returns")
    }

    private fun stopThenLateExit() {
        awaitStopRequest(exitCode = null)
        Thread {
            Thread.sleep(500)
            exitProcess(9)
        }.start()
    }

    /** Returns from main, leaving a worker that never ends; the worker says so once main has returned. */
    private fun mainReturnsBeforeTheRequest(exitCode: Int?) {
        check(stopOnShutdownRequest(exitCode).isSuccess()) { "not installed" }
        val mainThread = Thread.currentThread()
        Thread {
            mainThread.join()
            println("main returned")
            CountDownLatch(1).await()
        }.start()
        println("ready")
    }

    private fun startEndlessWorker() {
        Thread { CountDownLatch(1).await() }.start()
    }

    private fun addSlowAppHook() {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                Thread.sleep(500)
                println("app hook finished")
            },
        )
    }

    private fun kernHookIsWaiting(): Boolean = Thread.getAllStackTraces().keys.any {
        it.name == "kern shutdown wait" && it.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
    }

    private fun install(
        exitCode: Int? = null,
        handler: (ShutdownRequest) -> Unit,
    ) {
        val installed = onShutdownRequest(exitCode, handler)
        check(installed.isSuccess()) { "not installed: $installed" }
    }
}
