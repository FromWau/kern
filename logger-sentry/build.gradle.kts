plugins {
    id("kern-module")
}

description = "A Sentry sink for kern:logger: SentrySink sends each entry as a Sentry event, breadcrumb or log, " +
    "each from its own level, through the Sentry Kotlin Multiplatform SDK the app initialises."

kotlin {
    // The same targets as logger, so any code that logs can add this sink.
    kernLoggerTargets()

    // Declaring a source set by hand switches the default hierarchy off; re-applying it keeps nativeMain and webMain.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // The JVM and Android, where Sentry's multiplatform SDK runs on sentry-java and the sink calls that directly.
        val jvmAndroidMain = create("jvmAndroidMain") {
            dependsOn(commonMain.get())
        }
        listOf(jvmMain, androidMain).forEach { it.get().dependsOn(jvmAndroidMain) }

        commonMain.dependencies {
            // api: SentrySink is a LogSink, and its failures are kern:logger's SinkError.
            api(project(":logger"))

            // No Sentry type is in the API; the app declares Sentry itself to call Sentry.init.
            implementation(libs.sentry.kotlin.multiplatform)
        }

        commonTest.dependencies {
            implementation(project(":result-test"))
        }
    }
}

// LiveSentryTest sends to the project SENTRY_DSN names, set in the environment or the root .env, and is skipped
// without it. The DSN lands in the build output only, compiled into the JVM tests, never into a published artifact.
// Only liveSentryTest runs it, so no other test task or check ever sends.
val liveSentryConfig = tasks.register("liveSentryConfig") {
    description = "Generates LiveSentryConfig, the Sentry DSN LiveSentryTest sends to."
    val dsn = dotEnv.provider("SENTRY_DSN").orElse("")
    val outputDir = layout.buildDirectory.dir("generated/liveSentryConfig/kotlin")
    inputs.property("dsn", dsn)
    outputs.dir(outputDir)
    doLast {
        fun kotlinString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\', '"', '$' -> append('\\').append(char)
                    else -> append(char)
                }
            }
            append('"')
        }

        val value = dsn.get().takeIf { it.isNotBlank() }?.let(::kotlinString) ?: "null"
        val file = outputDir.get().file("com/fromwau/kern/logger/sentry/LiveSentryConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.fromwau.kern.logger.sentry
            |
            |/** The Sentry project LiveSentryTest sends to: SENTRY_DSN from the environment or the root .env. */
            |internal data object LiveSentryConfig {
            |    val dsn: String? = $value
            |}
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.jvmTest { kotlin.srcDir(liveSentryConfig) }

val liveSentryTestClass = "com.fromwau.kern.logger.sentry.LiveSentryTest"

tasks.withType<Test>().matching { it.name != "liveSentryTest" }.configureEach {
    filter.excludeTestsMatching(liveSentryTestClass)
}

tasks.register<Test>("liveSentryTest") {
    description = "Sends a few entries to the Sentry project SENTRY_DSN names; skipped without it."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    useJUnitPlatform()
    filter.includeTestsMatching(liveSentryTestClass)
    // Sending is the point of running it, so it never counts as up to date.
    outputs.upToDateWhen { false }
}
