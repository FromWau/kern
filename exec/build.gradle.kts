import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kern-module")
}

description = "Child processes for Kotlin Multiplatform on the JVM and Linux: start a program with an argument list, " +
    "read its output as it arrives, and stop it when you decide to."

kotlin {
    compilerOptions {
        // SystemStarter is an expect object, which Kotlin still marks as Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    linuxArm64()

    // Starting a child is C, from src/nativeInterop/cinterop/spawn.def: only async-signal-safe calls may run between
    // fork and exec, and Kotlin is not one.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("spawn")
    }

    sourceSets {
        commonMain.dependencies {
            // api: start returns a Result.
            api(project(":result"))
            // api: a Command's working directory is a Path.
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // Granted as an app would, which keeps the JDK's warning on the first native call out of the output.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
