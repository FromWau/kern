plugins {
    id("kern-module")
}

description = "Writes kern logger entries on a background writer with a bounded queue, so the thread that logs " +
    "never waits for the disk."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":logger"))
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.atomicfu)
        }
    }
}
