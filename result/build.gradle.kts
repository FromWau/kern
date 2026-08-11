plugins {
    id("kern-module")
}

description = "A typed-error Result for Kotlin Multiplatform: an expected failure is a value carrying " +
    "your own error type, not a thrown exception."

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
