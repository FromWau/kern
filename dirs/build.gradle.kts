plugins {
    id("kern-module")
}

description = "Where an app's config, data, state, cache and temp belong on every Kotlin Multiplatform target."

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }
    }
}
