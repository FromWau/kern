plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    `maven-publish`
}

val jdkVersion = libs.versions.jdk.get().toInt()

group = "com.fromwau.kern"
version = libs.versions.kernVersion.get()

val licenseResource = tasks.register<Copy>("licenseResource") {
    description = "Copies the project LICENSE into the common resources as META-INF/LICENSE-kern.txt."
    group = LifecycleBasePlugin.BUILD_GROUP
    from(rootProject.file("LICENSE")) { rename { "LICENSE-kern.txt" } }
    into(layout.buildDirectory.dir("generated/license/META-INF"))
}

val repoSlug = "FromWau/kern"
val repoUrl = "https://github.com/$repoSlug"

kotlin {
    explicitApi()

    jvmToolchain(jdkVersion)

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.fromwau.kern.result"
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
            baseName = "KernResult"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            resources.srcDir(licenseResource.map { it.destinationDir.parentFile })
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

val mavenUser = env.fetchOrNull("MAVEN_USERNAME")
val mavenToken = env.fetchOrNull("MAVEN_TOKEN")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "kern-result"
            description = "A typed-error Result for Kotlin Multiplatform: an expected failure is a " +
                "value carrying your own error type, not a thrown exception."
            url = repoUrl
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            scm {
                url = repoUrl
                connection = "scm:git:$repoUrl.git"
                developerConnection = "scm:git:ssh://git@github.com/$repoSlug.git"
            }
        }
    }

    repositories {
        maven {
            name = "vps"
            url = uri("https://maven.frommhund.xyz/releases")
            credentials {
                username = mavenUser.orEmpty()
                password = mavenToken.orEmpty()
            }
            authentication { create<BasicAuthentication>("basic") }
        }
    }
}

val hasMavenUser = !mavenUser.isNullOrBlank()
val hasMavenToken = !mavenToken.isNullOrBlank()

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        require(hasMavenUser) { "MAVEN_USERNAME is not set. Copy .env.example to .env and fill it in." }
        require(hasMavenToken) { "MAVEN_TOKEN is not set. Copy .env.example to .env and fill it in." }
    }
}
