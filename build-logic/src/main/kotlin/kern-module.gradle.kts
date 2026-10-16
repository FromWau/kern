import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.EnvSpec

// The Android namespace and framework name are derived from the Gradle project name, like the coordinates
// kern-publish sets, so a new module declares none of them. The one thing it must set is `description`.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("kern-publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String = libs.findVersion(alias).get().requiredVersion

val buildJdk = catalogVersion("jdk-build").toInt()
val targetJdk = catalogVersion("jdk-target").toInt()
val moduleName = project.name

// A module name may hold a hyphen, as `result-test` does, which neither a namespace nor a framework name may.
val namespaceName = moduleName.replace('-', '.')
val frameworkName = moduleName.split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

kotlin {
    explicitApi()

    compilerOptions {
        // Every non-Unit return in kern, tests included, is must-use, so a dropped Result warns here and for consumers.
        freeCompilerArgs.add("-Xreturn-value-checker=full")
    }

    jvmToolchain(buildJdk)

    android {
        compileSdk = catalogVersion("android-compileSdk").toInt()
        minSdk = catalogVersion("android-minSdk").toInt()
        namespace = "com.fromwau.kern.$namespaceName"
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(targetJdk.toString())
        }
    }

    jvm {
        compilerOptions {
            // Set explicitly: the toolchain would make it the build JDK's, which -Xjdk-release refuses.
            jvmTarget = JvmTarget.fromTarget(targetJdk.toString())
            // Fails the build on a JDK API newer than the target, which would otherwise reach a consumer as a
            // NoSuchMethodError.
            freeCompilerArgs.add("-Xjdk-release=$targetJdk")
        }
        // The Kotlin plugin leaves this attribute unset for a multiplatform jvm() target.
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, targetJdk)
    }
    linuxX64()
    mingwX64()

    listOf(
        macosArm64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "Kern$frameworkName"
            isStatic = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}

val jvmTestOnTarget = tasks.register<Test>("jvmTestOnTarget") {
    description = "Runs the JVM tests on JDK $targetJdk, the oldest a consumer may run."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(targetJdk) }
}
tasks.named("check") { dependsOn(jvmTestOnTarget) }

// The Kotlin plugin adds its own repositories for Node.js, Yarn and Binaryen, which settings.gradle.kts forbids and
// declares itself instead.
// Every EnvSpec loses its download URL, so D8, Wasmtime and Swc need their repository added to settings before use.
gradle.projectsEvaluated {
    listOf(project, rootProject).forEach { owner ->
        owner.extensions.extensionsSchema
            .filter { EnvSpec::class.java.isAssignableFrom(it.publicType.concreteClass) }
            .forEach { (owner.extensions.getByName(it.name) as EnvSpec<*>).downloadBaseUrl.set(null as String?) }
    }
}
