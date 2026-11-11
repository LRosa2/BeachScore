// Top-level build file. AGP 9 has built-in Kotlin support for Android modules, so
// `org.jetbrains.kotlin.android` is intentionally not used; the Compose compiler plugin
// pins the Kotlin Gradle plugin version for the build.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
