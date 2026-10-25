plugins {
    alias(libs.plugins.android.library) apply false
    // Not used for Compose: AGP 9 compiles Kotlin with the Kotlin Gradle plugin this puts on the build classpath,
    // which pins Kotlin to the catalog version instead of AGP's bundled one.
    alias(libs.plugins.kotlin.compose) apply false
}
