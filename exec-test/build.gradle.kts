plugins {
    id("kern-module")
}

description = "Test doubles for kern:exec: a scripted Starter that runs no process."

kotlin {
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":exec"))

            implementation(libs.kotlinx.atomicfu)
            // api: FakeChild is a SuspendingChild, which it awaits and blocks on through kotlinx-coroutines.
            api(project(":exec-coroutines"))
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
