plugins {
    id("kern-module")
}

description = "Coroutine helpers for kern:fs: a file lock that waits for another holder by suspending instead of " +
    "blocking the thread, and a flow of a file's changes."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android watch a folder through the same java.nio WatchService.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        commonMain.dependencies {
            api(project(":fs"))
            // api: changes() returns a Flow.
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(project(":fs-test"))
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
