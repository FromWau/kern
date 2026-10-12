import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.EnvSpec

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

    // Tests run on Node.js only: the browser targets share every line of web code with it, and a browser test
    // task needs a browser installed.
    js {
        browser { testTask { enabled = false } }
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
        nodejs()
    }

    sourceSets {
        // Every target with standard streams and a terminal to ask: all but the browser-hosted JS and Wasm.
        val stdioMain = create("stdioMain") {
            dependsOn(commonMain.get())
        }
        nativeMain.get().dependsOn(stdioMain)
        val stdioTest = create("stdioTest") {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(stdioTest)
        nativeTest.get().dependsOn(stdioTest)

        // JVM and Android append to the log file through the same java.io call.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(stdioMain)
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

            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        stdioMain.dependencies {
            // The console half: tty/NO_COLOR/FORCE_COLOR policy, Windows VT + UTF-8, broken pipes.
            implementation(project(":terminal"))
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The Kotlin plugin adds its own repositories for Node.js, Yarn and Binaryen, which settings.gradle.kts forbids and
// declares itself instead.
gradle.projectsEvaluated {
    listOf(project, rootProject).forEach { owner ->
        owner.extensions.extensionsSchema
            .filter { EnvSpec::class.java.isAssignableFrom(it.publicType.concreteClass) }
            .forEach { (owner.extensions.getByName(it.name) as EnvSpec<*>).downloadBaseUrl.set(null as String?) }
    }
}
