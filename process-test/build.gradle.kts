plugins {
    id("kern-module")
}

description = "A test ShutdownSource for kern:process: FakeShutdownSource sends a request to the installed " +
    "handler or stop on the calling thread and records the exit code, without ending the process."

kotlin {
    // The same targets as process, so the tests of anything that takes ShutdownSource can use this one.
    kernTerminalTargets()
    js {
        // The process module's own tests are bundled as kern-process-test, the name this module's JS file would get.
        outputModuleName = "kern-process-testing"
    }

    sourceSets {
        commonMain.dependencies {
            // api: FakeShutdownSource is a ShutdownSource and fails with ShutdownErrors.
            api(project(":process"))
        }
    }
}
