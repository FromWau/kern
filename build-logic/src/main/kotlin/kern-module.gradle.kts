import co.uzzu.dotenv.gradle.DotEnvRoot

// Coordinates, Android namespace, framework name and POM name are all derived from the Gradle project
// name, so a new module declares none of them. The one thing it must set is `description`.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(alias: String): String = libs.findVersion(alias).get().requiredVersion

val jdkVersion = catalogVersion("jdk").toInt()

group = "com.fromwau.kern"
version = catalogVersion("kernVersion")

val moduleName = project.name
val repoSlug = "FromWau/kern"
val repoUrl = "https://github.com/$repoSlug"

val licenseResource = tasks.register<Copy>("licenseResource") {
    val licenseName = "LICENSE-kern-$moduleName.txt"

    description = "Copies the project LICENSE into the common resources as META-INF/$licenseName."
    group = LifecycleBasePlugin.BUILD_GROUP
    inputs.property("licenseName", licenseName)
    from(rootProject.file("LICENSE")) { rename { licenseName } }
    into(layout.buildDirectory.dir("generated/license/META-INF"))
}

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
        commonMain {
            resources.srcDir(licenseResource.map { it.destinationDir.parentFile })
        }

        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}

val env = extensions.getByType<DotEnvRoot>()
val mavenUser = env.fetchOrNull("MAVEN_USERNAME")
val mavenToken = env.fetchOrNull("MAVEN_TOKEN")

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "kern-$moduleName"
            // Read lazily: the module sets its description after applying this plugin.
            description.set(provider { project.description })
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
