plugins {
    id("kern-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A reactive Kotlin Multiplatform logger: buffers until configured, then applies every " +
    "runtime change to the very next line."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: both types appear in the public surface (Logger.state, LoggerRuntimeState.file).
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)

            // The console half: tty/NO_COLOR/FORCE_COLOR policy, Windows VT + UTF-8, broken pipes.
            implementation(project(":terminal"))

            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
