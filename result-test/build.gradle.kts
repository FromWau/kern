plugins {
    id("kern-module")
}

description = "Test assertions for kern's Result: the value of a success or the error you expected, and a " +
    "failure naming what came instead."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlin.test)
        }
    }
}
