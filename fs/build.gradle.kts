plugins {
    id("kern-module")
}

description = "Path helpers for Kotlin Multiplatform that report failures as typed errors instead of throwing: " +
    "build file paths, read, write by rename or in place, list, walk, move, delete and lock files, and claim a " +
    "run's temp folder."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom sets below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android look paths up through the same java.nio call, which is the one API here that keeps a
        // missing file apart from one it may not look at.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        // Linux and Apple lock a file with fcntl, which Windows does not have; mingw keeps its own LockFileEx.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        appleMain.get().dependsOn(posixMain)

        commonMain.dependencies {
            // api: AppDirs and Path appear in the public signatures, and FileError is an IError.
            api(project(":dirs"))
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }
    }
}
