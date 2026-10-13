plugins {
    id("kern-module")
}

description = "kern's Result with coroutines: catching in suspending code, flows of results, and fallible work " +
    "run in parallel that stops at the first error."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: Result and Flow appear in this module's signatures.
            api(project(":result"))
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
