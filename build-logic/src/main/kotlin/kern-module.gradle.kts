// The Android namespace and framework name are derived from the Gradle project name, like the coordinates
// kern-publish sets, so a new module declares none of them. The one thing it must set is `description`.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("kern-publish")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String = libs.findVersion(alias).get().requiredVersion

val jdkVersion = catalogVersion("jdk").toInt()
val moduleName = project.name

kotlin {
    explicitApi()

    jvmToolchain(jdkVersion)

    android {
        compileSdk = catalogVersion("android-compileSdk").toInt()
        minSdk = catalogVersion("android-minSdk").toInt()
        namespace = "com.fromwau.kern.$moduleName"
    }

    jvm {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jdkVersion)
    }
    linuxX64()
    mingwX64()

    listOf(
        macosArm64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "Kern${moduleName.replaceFirstChar { it.uppercase() }}"
            isStatic = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}
