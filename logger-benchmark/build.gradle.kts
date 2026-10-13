// Not published: measures what a log call costs the thread that logs. Run with `./gradlew :logger-benchmark:benchmark`.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlinAllOpen)
    alias(libs.plugins.kotlinxBenchmark)
}

val buildJdk = libs.versions.jdk.build.get().toInt()

kotlin {
    jvmToolchain(buildJdk)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":logger"))
            implementation(project(":logger-async"))
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

// JMH subclasses each @State class, which Kotlin makes final.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        named("main") {
            mode = "avgt"
            outputTimeUnit = "ns"
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}

// Sustained load rather than per-call averages: many threads, a million entries, and a check of the file after.
tasks.register<JavaExec>("stress") {
    description = "Logs a million entries from up to 256 threads and checks every line that lands in the file."
    group = "verification"
    val main = kotlin.jvm().compilations.getByName("main")
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass = "com.fromwau.kern.logger.benchmark.StressKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(buildJdk) }
    workingDir = projectDir
    providers.gradleProperty("entries").orNull?.let { args(it) }
}
