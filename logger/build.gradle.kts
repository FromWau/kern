plugins {
    id("kern-module")
    alias(libs.plugins.kotlinSerialization)
}

description = "A reactive Kotlin Multiplatform logger: logs with defaults at once, applies every change to " +
    "the very next line, and can hold startup entries until your config is read."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom sets below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android append to the log file through the same java.io call.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        // Linux and Apple append with POSIX open and write, which Windows does not have.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        appleMain.get().dependsOn(posixMain)

        commonMain.dependencies {
            // api: both types appear in the public surface (Logger.state, LoggerConfig.file).
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)

            // The console half: tty/NO_COLOR/FORCE_COLOR policy, Windows VT + UTF-8, broken pipes.
            implementation(project(":terminal"))

            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
