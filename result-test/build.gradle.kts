plugins {
    id("kern-module")
}

description = "Test assertions for kern's Result: the value of a success or the error you expected, and a " +
    "failure naming what came instead."

kotlin {
    // The same targets as result, so the tests of every module built on result can use these assertions.
    kernAllTargets()

    sourceSets {
        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlin.test)
        }
    }
}
