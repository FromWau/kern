plugins {
    id("kern-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A Kotlin Multiplatform logger that never throws: every destination is a sink with its own queue " +
    "and worker, so a log call never waits for the disk, and every failure is a typed error."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom sets below keeps both.
    applyDefaultHierarchyTemplate()

    kernLoggerTargets()

    sourceSets {
        // Every target with standard streams, a file system and a thread that may block: all but the
        // browser-hosted JS and Wasm.
        val stdioMain = create("stdioMain") {
            dependsOn(commonMain.get())
        }
        nativeMain.get().dependsOn(stdioMain)
        val stdioTest = create("stdioTest") {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(stdioTest)
        nativeTest.get().dependsOn(stdioTest)

        // JVM and Android block a thread and track the writing sink the same way.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(stdioMain)
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        // Linux and Apple read the local zone through POSIX localtime_r, which Windows does not have.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        appleMain.get().dependsOn(posixMain)

        // Where a program owns a terminal: StdoutSink and StderrSink. Android and iOS route both streams nowhere a
        // user reads, so they are left out.
        val desktopMain = create("desktopMain") {
            dependsOn(stdioMain)
        }
        listOf(jvmMain, linuxMain, mingwMain, macosMain).forEach { it.get().dependsOn(desktopMain) }

        commonMain.dependencies {
            // api: Result, EmptyResult and ResultError appear in every public signature; StateFlow in sinks and
            // health.
            api(project(":result"))
            api(libs.kotlinx.coroutines.core)

            implementation(project(":terminal"))

            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(project(":logger-test"))
            implementation(project(":result-test"))
            implementation(project(":terminal-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
