import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Coordinates and the POM name are derived from the Gradle project name, so a module declares neither. The one
// thing it must set is `description`.

plugins {
    id("com.fromwau.dotenv")
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.fromwau.kern"
version = libs.findVersion("kernVersion").get().requiredVersion

val moduleName = project.name
val repoSlug = "FromWau/kern"
val repoUrl = "https://github.com/$repoSlug"

val licenseResource = tasks.register<Copy>("licenseResource") {
    val licenseName = "LICENSE-kern-$moduleName.txt"

    description = "Copies the project LICENSE into the resources as META-INF/$licenseName."
    group = LifecycleBasePlugin.BUILD_GROUP
    inputs.property("licenseName", licenseName)
    from(rootDir.resolve("LICENSE")) { rename { licenseName } }
    into(layout.buildDirectory.dir("generated/license/META-INF"))
}
val licenseResources = licenseResource.map { it.destinationDir.parentFile }

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.commonMain { resources.srcDir(licenseResources) }
    }
}

val mavenUser = dotEnv["MAVEN_USERNAME"]
val mavenToken = dotEnv["MAVEN_TOKEN"]

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

// A version names exactly one commit: publishing needs a clean checkout whose HEAD carries the tag v<version>.
val releaseTag = "v$version"
val headTags: Provider<String> = providers.exec {
    commandLine("git", "tag", "--points-at", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText
val uncommitted: Provider<String> = providers.exec {
    commandLine("git", "status", "--porcelain")
    isIgnoreExitValue = true
}.standardOutput.asText

tasks.withType<PublishToMavenRepository>().configureEach {
    // Copied into the task: a doFirst reading these from the script holds a script reference, which the
    // configuration cache cannot serialize.
    val tag = releaseTag
    val tags = headTags
    val dirty = uncommitted
    val hasUser = hasMavenUser
    val hasToken = hasMavenToken

    doFirst {
        require(tag in tags.get().lines()) { "Publishing $tag needs HEAD tagged $tag." }
        require(dirty.get().isBlank()) { "Publishing needs a clean checkout. Commit or stash everything first." }
        require(hasUser) { "MAVEN_USERNAME is not set. Copy .env.example to .env and fill it in." }
        require(hasToken) { "MAVEN_TOKEN is not set. Copy .env.example to .env and fill it in." }
    }
}
