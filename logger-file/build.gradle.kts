plugins {
    id("kern-module")
}

description = "A log file sink for kern:logger: FileSink appends each entry to a file in one write, starts a new " +
    "file when the old one is rotated or deleted, and reports every failure as kern:fs's typed FileError."

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: FileSink is a LogSink and takes a Path, and its failures carry kern:fs's FileError.
            api(project(":logger"))
            api(project(":fs"))

            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
