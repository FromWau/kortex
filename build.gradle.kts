plugins {
    // Loads build-logic, and with it the Kotlin Gradle plugin, once for every module rather than once each.
    id("kortex-library") apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
