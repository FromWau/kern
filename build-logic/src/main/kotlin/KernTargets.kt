import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/** JS and Wasm, for the browser and Node.js. */
fun KotlinMultiplatformExtension.kernWebTargets() {
    // Tests run on Node.js only: the browser targets share every line of web code with it, and a browser test
    // task needs a browser installed.
    js {
        browser { testTask { enabled = false } }
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
        nodejs()
    }
}

/** kern:logger's targets on top of the kern-module convention's, which kern:logger-test shares. */
fun KotlinMultiplatformExtension.kernLoggerTargets() {
    kernWebTargets()
}

/**
 * kern:terminal's targets on top of the kern-module convention's, which kern:terminal-test shares: every target a
 * command-line program may run on. tvOS and watchOS have none.
 */
fun KotlinMultiplatformExtension.kernTerminalTargets() {
    linuxArm64()
    androidNativeArm64()
    androidNativeX64()
    androidNativeArm32()
    androidNativeX86()

    kernWebTargets()
    kernWasiTarget()
}

/**
 * Every non-deprecated target on top of the kern-module convention's: kern:result, the base any kern module builds
 * on, and kern:result-test, which every module's tests use.
 */
fun KotlinMultiplatformExtension.kernAllTargets() {
    linuxArm64()
    kernWebTargets()
    kernWasiTarget()

    tvosArm64()
    tvosSimulatorArm64()
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    androidNativeArm64()
    androidNativeX64()
    androidNativeArm32()
    androidNativeX86()
}

/** WASI on Node.js, whose test run silences every Node `ExperimentalWarning`, WASI's own among them. */
@OptIn(ExperimentalWasmDsl::class)
private fun KotlinMultiplatformExtension.kernWasiTarget() {
    wasmWasi {
        nodejs {
            testTask { nodeJsArgs += "--disable-warning=ExperimentalWarning" }
        }
    }
}

/**
 * The `threadedTest` source set the JVM and native tests share, for tests that run on the JVM and native, such as one
 * that runs work on several threads at once, which the one JS and Wasm thread cannot. Call it after the targets, once
 * the default hierarchy is applied.
 */
fun KotlinMultiplatformExtension.kernThreadedTest(): KotlinSourceSet {
    val threadedTest = sourceSets.create("threadedTest") { dependsOn(sourceSets.getByName("commonTest")) }
    sourceSets.getByName("jvmTest").dependsOn(threadedTest)
    sourceSets.getByName("nativeTest").dependsOn(threadedTest)
    return threadedTest
}
