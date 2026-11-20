plugins {
    id("kern-module")
}

description = "Coroutine helpers for kern:exec: a child's output as a Flow and its exit as a suspending call."

kotlin {
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":exec"))
            // api: the output is a Flow.
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":exec-test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // Granted as an app would, which keeps the JDK's warning on the first native call out of the output.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
