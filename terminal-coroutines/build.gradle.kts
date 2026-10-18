plugins {
    id("kern-module")
}

description = "kern:terminal for coroutine code: writes a Flow to standard output in chunks, stopping at the first " +
    "failed write to standard output."

kotlin {
    kernTerminalTargets()

    sourceSets {
        commonMain.dependencies {
            // api: Terminal, WriteError and Flow appear in this module's signatures.
            api(project(":terminal"))
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(project(":terminal-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
