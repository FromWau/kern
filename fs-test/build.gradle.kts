plugins {
    id("kern-module")
}

description = "Test helpers for kern:fs: a fresh scratch folder under the temp folder TMPDIR names, symlinks, " +
    "hard links, permission bits and FIFOs made with fs's typed FileError, and the leftovers a killed write leaves."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and every leaf
    // under it. Re-applying it before the custom sets below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // JVM and Android make links and set modes through the same java.nio calls.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        // Linux and Apple share link, chmod and mkfifo, which Windows does not have.
        val posixMain = create("posixMain") {
            dependsOn(nativeMain.get())
        }
        linuxMain.get().dependsOn(posixMain)
        appleMain.get().dependsOn(posixMain)

        // Linux and Apple answer a symlink loop with ELOOP and a chmod of another user's file with EPERM.
        val posixTest = create("posixTest") {
            dependsOn(nativeTest.get())
        }
        linuxTest.get().dependsOn(posixTest)
        appleTest.get().dependsOn(posixTest)

        // Where mkfifo makes a FIFO: Linux, Apple, and the JVM through the mkfifo command. Windows has none.
        val fifoTest = create("fifoTest") {
            dependsOn(commonTest.get())
        }
        jvmTest.get().dependsOn(fifoTest)
        linuxTest.get().dependsOn(fifoTest)
        appleTest.get().dependsOn(fifoTest)

        commonMain.dependencies {
            // api: every helper takes a Path and fails with fs's FileError, which a test asserts on with result-test.
            api(project(":fs"))
            api(project(":result-test"))
        }
    }
}
