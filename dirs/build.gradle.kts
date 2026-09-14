plugins {
    id("kern-module")
}

description = "Where an app's config, data, state, cache and temp belong on every Kotlin Multiplatform target, " +
    "and Path helpers that report failures as typed errors instead of throwing."

kotlin {
    android {
        minSdk = 26
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }
    }
}
