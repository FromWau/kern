plugins {
    id("kern-module")
}

description = "A file lock for coroutine code: waits for another holder by suspending instead of blocking the thread."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":fs"))
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
