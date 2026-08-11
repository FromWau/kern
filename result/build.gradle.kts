plugins {
    id("kern-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A typed-error Result for Kotlin Multiplatform: an expected failure is a value carrying " +
    "your own error type, not a thrown exception."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // compileOnly, never implementation: on JVM and Android a consumer that does not serialize a
            // Result resolves only the standard library, because nothing loads the serializer.
            compileOnly(libs.kotlinx.serialization.core)
        }

        nativeMain.dependencies {
            // NOTE: compileOnly is not yet supported on native, so it has to be exposed as api there and
            // a native consumer resolves it whether or not they serialize:
            // https://youtrack.jetbrains.com/issue/KT-78948
            // Same split kotlinx-datetime publishes. Drop this once the issue closes.
            api(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
