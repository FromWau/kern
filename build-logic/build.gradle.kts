plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.android.gradlePlugin)

    // Only to read the `env` extension the root project installs; build-logic never applies dotenv.
    compileOnly(libs.dotenv.gradlePlugin)
}
