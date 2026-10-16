import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
 * Every non-deprecated target on top of the kern-module convention's: kern:result, the base any kern module builds
 * on, and kern:result-test, which every module's tests use.
 */
fun KotlinMultiplatformExtension.kernAllTargets() {
    linuxArm64()
    kernWebTargets()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

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
