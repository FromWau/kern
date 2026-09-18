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

    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android look paths up through the same java.nio call, which is the one API here that keeps a
        // missing file apart from one it may not look at.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }
    }
}
