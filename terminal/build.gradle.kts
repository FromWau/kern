import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("kern-module")
}

description = "Terminal IO for Kotlin Multiplatform: stdout/stderr, tty and width detection, and one " +
    "ANSI colour policy that honours NO_COLOR, FORCE_COLOR and a piped handle."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and
    // every leaf under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    // Every target a command-line program may run on, on top of the convention's; tvOS and watchOS have none.
    linuxArm64()
    androidNativeArm64()
    androidNativeX64()
    androidNativeArm32()
    androidNativeX86()

    kernWebTargets()
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            // api: Terminal.writeResult returns a Result.
            api(project(":result"))
        }

        // JVM and Android share their stdio wiring (the sinks that keep a failed write) and differ only in
        // the tty probe, so the shared half lives one level above both.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
    }
}
