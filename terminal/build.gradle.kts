plugins {
    id("kern-module")
}

description = "Terminal IO for Kotlin Multiplatform: stdout/stderr, tty and width detection, and one " +
    "ANSI colour policy that honours NO_COLOR, FORCE_COLOR and a piped handle."

kotlin {
    // Declaring a source set by hand switches the default hierarchy off, which strands nativeMain and
    // every leaf under it. Re-applying it before the custom set below keeps both.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // api: Terminal.writeResult returns a Result.
            api(project(":result"))
        }

        // JVM and Android share their stdio wiring (the sinks that keep a failed write) and differ only in
        // the tty probe, so the shared half lives one level above both.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
    }
}
