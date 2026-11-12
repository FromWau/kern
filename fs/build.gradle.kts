plugins {
    id("kern-module")
}

description = "Path helpers for Kotlin Multiplatform that report failures as typed errors instead of throwing: " +
    "build file paths, read, write by rename or in place, append, list, walk, move, delete and lock files, and " +
    "claim a run's temp folder."

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
            // api: AppDirs and Path appear in the public signatures, and FileError is a ResultError.
            api(project(":dirs"))
            api(project(":result"))
            api(libs.kotlinx.io.core)
        }

        commonTest.dependencies {
            implementation(project(":fs-test"))
            implementation(project(":result-test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val uidProcTestClass = "com.fromwau.kern.fs.CurrentUserIdProcJvmTest"

val jvmTestWithoutTempFolder = tasks.register<Test>("jvmTestWithoutTempFolder") {
    description = "Runs the uid test with java.io.tmpdir naming a folder that does not exist."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    useJUnitPlatform()
    filter.includeTestsMatching(uidProcTestClass)
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("missing-tmpdir").get().asFile.path)
}
tasks.named("check") { dependsOn(jvmTestWithoutTempFolder) }
