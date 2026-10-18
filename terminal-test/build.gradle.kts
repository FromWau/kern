plugins {
    id("kern-module")
}

description = "A test Terminal for kern:terminal: RecordingTerminal keeps what each stream was sent and fails a " +
    "stream's writes when told to."

kotlin {
    // The same targets as terminal, so the tests of anything that writes to a terminal can use this one.
    kernTerminalTargets()
    js {
        // The terminal's own tests, which use this module, are bundled as kern-terminal-test, the name this module's
        // JS file would get.
        outputModuleName = "kern-terminal-testing"
    }

    sourceSets {
        commonMain.dependencies {
            // api: RecordingTerminal is a Terminal and reports WriteErrors.
            api(project(":terminal"))

            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
