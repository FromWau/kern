plugins {
    id("kern-module")
}

description = "Test sinks for kern:logger: TestSink records every entry and waits for the next one, and " +
    "RecordingSink wraps a sink of your own to record each write with what it returned."

kotlin {
    // The same targets as logger, so the tests of anything that logs can use these sinks.
    kernLoggerTargets()
    js {
        // The logger's own tests, which use this module, are bundled as kern-logger-test, the name this module's
        // JS file would get.
        outputModuleName = "kern-logger-testing"
    }

    sourceSets {
        commonMain.dependencies {
            // api: both sinks are LogSinks, hand out Flows, and fail a test through kotlin-test.
            api(project(":logger"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlin.test)

            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
