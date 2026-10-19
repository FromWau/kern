import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.Family

plugins {
    id("kern-module")
}

description = "Shutdown requests for Kotlin Multiplatform: one handler on an ordinary thread, or a flag your loop " +
    "polls, for Ctrl-C, SIGTERM and SIGHUP, then the shell's 128+N exit code."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    kernTerminalTargets()

    // The signal handler is C, from src/nativeInterop/cinterop/signals.def: Kotlin may not run inside one.
    targets.withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family != Family.MINGW }
        .configureEach { compilations.getByName("main").cinterops.create("signals") }

    // The Linux tests signal a child process: this binary, built from the test sources.
    targets.withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family == Family.LINUX }
        .configureEach {
            val target = this
            binaries.executable("shutdownChild", listOf(NativeBuildType.DEBUG)) {
                compilation = compilations.getByName("test")
                entryPoint = "com.fromwau.kern.process.shutdownChildMain"
            }
            tasks.withType<KotlinNativeTest>()
                .matching { it.name == "${target.name}Test" }
                .configureEach {
                    val child = target.binaries.getExecutable("shutdownChild", NativeBuildType.DEBUG)
                    inputs.files(child.linkTaskProvider)
                    environment("KERN_PROCESS_CHILD", child.outputFile.absolutePath)
                }
        }

    sourceSets {
        commonMain.dependencies {
            // api: onShutdownRequest returns a Result.
            api(project(":result"))
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }

        // Tests that race calls on several threads, which only the JVM and native targets have.
        val threadsTest = create("threadsTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        jvmTest.get().dependsOn(threadsTest)
        nativeTest.get().dependsOn(threadsTest)

        // Linux and androidNative share the sigaction handler; mingw has console control events instead.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        androidNativeMain.get().dependsOn(posixMain)
    }
}
