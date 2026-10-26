plugins {
    id("kern-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A typed-error Result for Kotlin Multiplatform: an expected failure is a value carrying " +
    "your own error type, not a thrown exception."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    kernAllTargets()
    // A race test spins on one thread until another moves, which never happens on the single JS or Wasm thread.
    kernThreadedTest()

    sourceSets {
        commonMain.dependencies {
            // compileOnly, never implementation: on JVM and Android a consumer that does not serialize a
            // Result resolves only the standard library, because nothing loads the serializer.
            compileOnly(libs.kotlinx.serialization.core)
        }

        // NOTE: compileOnly is not yet supported on native, JS or Wasm, so it has to be exposed as api there and
        // a consumer on those targets resolves it whether or not they serialize:
        // https://youtrack.jetbrains.com/issue/KT-78948
        // Same split kotlinx-datetime publishes. Drop this once the issue closes.
        listOf(nativeMain, webMain, wasmWasiMain).forEach { sourceSet ->
            sourceSet.dependencies { api(libs.kotlinx.serialization.core) }
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
