plugins {
    id("kern-module")
}

description = "Where an app's config, data, state, cache and temp belong on every Kotlin Multiplatform target."

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Adding dependsOn edges by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Every target but Android, whose factory needs a Context, so BaseDirs.current() can make one without it.
        val jvmNativeMain = create("jvmNativeMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmNativeMain)
        nativeMain.get().dependsOn(jvmNativeMain)

        // Linux and Apple tell a private folder by its owner and mode bits, which Windows does not keep.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        appleMain.get().dependsOn(posixMain)

        commonMain.dependencies {
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }
    }
}
