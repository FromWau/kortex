// What every multiplatform module of kortex shares; each module adds its own plugins and dependencies.
plugins {
    kotlin("multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.fromwau.kortex"
version = libs.findVersion("kortexVersion").get().requiredVersion

kotlin {
    explicitApi()

    jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())

    jvm()

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
                implementation(libs.findLibrary("kern-result-test").get())
            }
        }
    }
}
