import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("kern-module")
}

description = "Coroutine helpers for kern:fs: a file lock that waits for another holder by suspending instead of " +
    "blocking the thread, and a flow of a file's changes."

// The JVM watches through inotify by FFM where the JDK has it, Java 22 on: those classes are compiled for 22 and sit in
// META-INF/versions/22 of the jar, where a JDK before 22 never looks.
val ffmJdk = 22

kotlin {
    jvm {
        val main = compilations.getByName("main")
        val java22 = compilations.create("java22") {
            // Against main's classes, not its jar, which holds these: associateWith would make that a cycle.
            compileDependencyFiles += main.output.classesDirs + main.compileDependencyFiles
            compileTaskProvider.configure {
                (this as KotlinJvmCompile).friendPaths.from(main.output.classesDirs)
                compilerOptions {
                    moduleName = main.compileTaskProvider.flatMap { it.compilerOptions.moduleName }
                    jvmTarget = JvmTarget.fromTarget(ffmJdk.toString())
                    // Main's arguments with its -Xjdk-release replaced, which would hold these to the JDK every
                    // consumer may run.
                    freeCompilerArgs.set(
                        main.compileTaskProvider
                            .flatMap { it.compilerOptions.freeCompilerArgs }
                            .map { args ->
                                args.filterNot { it.startsWith("-Xjdk-release=") } + "-Xjdk-release=$ffmJdk"
                            },
                    )
                }
            }
        }
        tasks.named<Jar>("jvmJar") {
            into("META-INF/versions/$ffmJdk") { from(java22.output.allOutputs) { exclude("META-INF/**") } }
            manifest { attributes("Multi-Release" to "true") }
        }
        tasks.named<org.gradle.jvm.tasks.Jar>("jvmSourcesJar") {
            from(java22.defaultSourceSet.kotlin) { into(java22.defaultSourceSet.name) }
        }
        tasks.named<Test>("jvmTest") {
            classpath += files(java22.output.allOutputs)
        }
    }

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
